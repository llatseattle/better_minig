
local kube = import 'external/kube_jsonnet/kube.libsonnet';
local envs = import 'prod/envs.libsonnet';
local redis = import 'jsonnet/redis.libsonnet';
local params = std.extVar("params");

local redis_server_name = params.name + "-redis";

local main_container = kube.Container("server") {
  // Environment specific values go in a map keyed by params.env,
  // one of which will be passed into params.env
  local images = envs.toEnvironmentMap(
    prod=params.image_base + "prod_tag",
    staging=params.image_base + "staging_tag",
    dev=params.image_base + "some_dev_tag",
    myns=params.local_image_name
  ),
  resources: {},
  image: images[params.env],
  ports_+: { grpc: { containerPort: std.parseInt(params.port) } },
  args: [std.toString(params.port), redis_server_name]
};

local deployment = kube.Deployment(params.name) {
  metadata+: {namespace: envs.getName(params.env)},
  spec+: {
    replicas: 1,
    template+: {
      spec+: {
        containers_+: {
          gb_fe: main_container,
}}}}};

local redis_depl = redis.Server(redis_server_name, envs.getName(params.env));
local redis_svc = redis.Service(redis_server_name, redis_depl.spec.template, envs.getName(params.env));

{
  [params.env + "-server.json"]: deployment,
  [params.env + "-redis.json"]: redis_depl, 
  [params.env + "-redis-svc.json"]: redis_svc, 
}

