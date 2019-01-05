/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.grpc_redis;

import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;
import static java.lang.Math.toRadians;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.grpc.examples.routeguide.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.redisson.Redisson;
import org.redisson.api.RGeo;
import org.redisson.api.GeoUnit;
import org.redisson.api.GeoPosition;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * A sample gRPC server that serve the RouteGuide (see route_guide.proto) service.
 */
public class RouteGuideServer {
  private static final Logger logger = Logger.getLogger(RouteGuideServer.class.getName());

  private final int port;
  private final Server server;

  /** Create a RouteGuide server listening on {@code port} using {@code featureFile} database. */
  public RouteGuideServer(int port, RedissonClient redisson) throws IOException {
    this(ServerBuilder.forPort(port), port, redisson, RouteGuideUtil.loadRedisGeo(redisson));
  }

  /** Create a RouteGuide server using serverBuilder as a base and features as data. */
  public RouteGuideServer(ServerBuilder<?> serverBuilder, int port, RedissonClient redisson, RGeo<String> geo) {
    this.port = port;
    logger.info("Initializing server with " + geo.size() + " features");
    server = serverBuilder.addService(new RouteGuideService(redisson, geo))
        .build();
  }

  /** Start serving requests. */
  public void start() throws IOException {
    server.start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may has been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        RouteGuideServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  /** Stop serving requests and shutdown resources. */
  public void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main method.  This comment makes the linter happy.
   */
  public static void main(String[] args) throws Exception {
    System.out.println("Using port " + args[0]);
    Config config = new Config();
    config.useSingleServer().setAddress("redis://" + args[1] + ":6379");
    RedissonClient redisson = Redisson.create(config);
    RouteGuideServer server = new RouteGuideServer(Integer.parseInt(args[0]), redisson);
    server.start();
    server.blockUntilShutdown();
  }

  /**
   * Our implementation of RouteGuide service.
   *
   * <p>See route_guide.proto for details of the methods.
   */
  private static class RouteGuideService extends RouteGuideGrpc.RouteGuideImplBase {
    private final RGeo<String> geo;
    private final RedissonClient redisson;
    private final ConcurrentMap<Point, List<RouteNote>> routeNotes =
        new ConcurrentHashMap<Point, List<RouteNote>>();

    RouteGuideService(RedissonClient redisson, RGeo<String> geo) {
      this.redisson = redisson;
      this.geo = geo;
    }

    /**
     * Gets the {@link Feature} at the requested {@link Point}. If no feature at that location
     * exists, an unnamed feature is returned at the provided location.
     *
     * @param request the requested location for the feature.
     * @param responseObserver the observer that will receive the feature at the requested point.
     */
    @Override
    public void getFeature(Point request, StreamObserver<Feature> responseObserver) {
      responseObserver.onNext(checkFeature(request));
      responseObserver.onCompleted();
    }

    /**
     * Gets all features contained within the given bounding {@link Rectangle}.
     *
     * @param request the bounding rectangle for the requested features.
     * @param responseObserver the observer that will receive the features.
     */
    @Override
    public void listFeatures(Rectangle request, StreamObserver<Feature> responseObserver) {
      int left = min(request.getLo().getLongitude(), request.getHi().getLongitude());
      int right = max(request.getLo().getLongitude(), request.getHi().getLongitude());
      int top = max(request.getLo().getLatitude(), request.getHi().getLatitude());
      int bottom = min(request.getLo().getLatitude(), request.getHi().getLatitude());
      Point center = Point.newBuilder()
        .setLongitude((top + bottom)/2)
        .setLatitude((left + right)/2)
        .build();
      double centerLatitude = RouteGuideUtil.getLatitude(center);
      double centerLongitude = RouteGuideUtil.getLongitude(center);
      int radius = calcDistance(request.getLo(), request.getHi())/2;  //in meters
      Map<String, GeoPosition> features = geo.radiusWithPosition(
        centerLongitude, centerLatitude, radius, GeoUnit.METERS);
      for (Map.Entry<String, GeoPosition> entry : features.entrySet()){
        Point location = RouteGuideUtil.getPoint(
          entry.getValue().getLongitude(),
          entry.getValue().getLatitude());
        responseObserver.onNext(
          Feature.newBuilder()
          .setName(entry.getKey())
          .setLocation(location)
          .build());
      }
      responseObserver.onCompleted();
    }

    /**
     * Gets a stream of points, and responds with statistics about the "trip": number of points,
     * number of known features visited, total distance traveled, and total time spent.
     *
     * @param responseObserver an observer to receive the response summary.
     * @return an observer to receive the requested route points.
     */
    @Override
    public StreamObserver<Point> recordRoute(final StreamObserver<RouteSummary> responseObserver) {
      return new StreamObserver<Point>() {
        int pointCount;
        int featureCount;
        int distance;
        Point previous;
        final long startTime = System.nanoTime();

        @Override
        public void onNext(Point point) {
          pointCount++;
          if (RouteGuideUtil.validate(checkFeature(point))) {
            featureCount++;
          }
          // For each point after the first, add the incremental distance from the previous point to
          // the total distance value.
          if (previous != null) {
            distance += calcDistance(previous, point);
          }
          previous = point;
        }

        @Override
        public void onError(Throwable t) {
          logger.log(Level.WARNING, "recordRoute cancelled");
        }

        @Override
        public void onCompleted() {
          long seconds = NANOSECONDS.toSeconds(System.nanoTime() - startTime);
          responseObserver.onNext(RouteSummary.newBuilder().setPointCount(pointCount)
              .setFeatureCount(featureCount).setDistance(distance)
              .setElapsedTime((int) seconds).build());
          responseObserver.onCompleted();
        }
      };
    }

    /**
     * Receives a stream of message/location pairs, and responds with a stream of all previous
     * messages at each of those locations.
     *
     * @param responseObserver an observer to receive the stream of previous messages.
     * @return an observer to handle requested message/location pairs.
     */
    @Override
    public StreamObserver<RouteNote> routeChat(final StreamObserver<RouteNote> responseObserver) {
      return new StreamObserver<RouteNote>() {
        @Override
        public void onNext(RouteNote note) {
          List<RouteNote> notes = getOrCreateNotes(note.getLocation());

          // Respond with all previous notes at this location.
          for (RouteNote prevNote : notes.toArray(new RouteNote[0])) {
            responseObserver.onNext(prevNote);
          }

          // Now add the new note to the list
          notes.add(note);
        }

        @Override
        public void onError(Throwable t) {
          logger.log(Level.WARNING, "routeChat cancelled");
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    /**
     * Get the notes list for the given location. If missing, create it.
     */
    private List<RouteNote> getOrCreateNotes(Point location) {
      List<RouteNote> notes = Collections.synchronizedList(new ArrayList<RouteNote>());
      List<RouteNote> prevNotes = routeNotes.putIfAbsent(location, notes);
      return prevNotes != null ? prevNotes : notes;
    }

    /**
     * Gets a feature within 10 meters of the given point.
     *
     * @param location the location to check.
     * @return The feature object at the point. Note that an empty name indicates no feature.
     */
    private Feature checkFeature(Point location) {
      String retName = "";
      Point.Builder retLocation = Point.newBuilder().mergeFrom(location);
      Map<String, GeoPosition> featuresWithPosition = geo.radiusWithPosition(
        RouteGuideUtil.getLatitude(location), RouteGuideUtil.getLatitude(location), 1000, GeoUnit.METERS);
      for (String featureName : featuresWithPosition.keySet()) {
        retName = featureName;
        GeoPosition position = featuresWithPosition.get(featureName);
        RouteGuideUtil.getPoint(position.getLongitude(), position.getLatitude());
        break;
      }

      return Feature.newBuilder().setName(retName).setLocation(retLocation.build()).build();
    }

    /**
     * Calculate the distance between two points using the "haversine" formula.
     * The formula is based on http://mathforum.org/library/drmath/view/51879.html.
     *
     * @param start The starting point
     * @param end The end point
     * @return The distance between the points in meters
     */
    private static int calcDistance(Point start, Point end) {
      int r = 6371000; // earth radius in meters
      double lat1 = toRadians(RouteGuideUtil.getLatitude(start));
      double lat2 = toRadians(RouteGuideUtil.getLatitude(end));
      double lon1 = toRadians(RouteGuideUtil.getLongitude(start));
      double lon2 = toRadians(RouteGuideUtil.getLongitude(end));
      double deltaLat = lat2 - lat1;
      double deltaLon = lon2 - lon1;

      double a = sin(deltaLat / 2) * sin(deltaLat / 2)
          + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2);
      double c = 2 * atan2(sqrt(a), sqrt(1 - a));

      return (int) (r * c);
    }
  }
}
