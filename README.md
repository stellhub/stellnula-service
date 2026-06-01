# Stellnula Service

Stellnula Service 是 StellHub 配置中心的服务端工程。Stellnula 的中文名是“星云”，英文完整名是 Nebula，定位为面向客户端 SDK 的配置查询、首次全量同步、运行态长轮询订阅、配置缓存和故障降级服务。

本仓库当前以 [stellhub/stellflux](https://github.com/stellhub/stellflux) 作为服务框架基座，优先使用 Spring Boot 3、Stellflux HTTP / gRPC starter、Caffeine 本地缓存、OpenTelemetry 能力和 PostgreSQL 持久化底座。

## 1. Problem analysis

配置中心不是简单的 KV 存储服务，而是应用配置、公共配置、作用域隔离、版本发布、变更审计、客户端订阅和故障恢复共同组成的运行时控制面。

当前设计约束如下：

- 配置中心运行态读多写少，写路径低频但风险高，读路径必须低延迟、高可用。
- PostgreSQL 作为配置内容、版本、发布、审计和恢复数据底座。
- DB 是启动时强依赖：服务端节点启动必须从 PostgreSQL 构建全量配置缓存。
- DB 是运行时弱依赖：客户端读请求不直接穿透 DB，运行态优先访问服务端内存缓存。
- 服务端通过定时刷新、版本号扫描或后续变更事件同步刷新本地缓存。
- 客户端 SDK 首次交互使用 HTTP，完成服务发现、身份校验、全量配置拉取和 gRPC 订阅信息获取。
- 首次 HTTP 之后的长轮询采用 gRPC 通信。
- 客户端 SDK 在应用启动时强制拉取远端全量配置，并写入本地文件和本地全量内存。
- 客户端 SDK 运行态异步更新本地内存和本地文件。
- 客户端故障重启时先读取本地文件恢复最后一次成功配置，再尝试访问远端刷新。

## 2. Design

### 2.1 架构分层

Stellnula Service 第一阶段划分为四层：

- Client SDK：负责启动拉取、gRPC 长轮询、本地文件缓存、本地全量内存缓存、配置变更监听和故障恢复。
- Data Plane：面向客户端提供 HTTP bootstrap、gRPC watch、全量拉取、增量拉取和客户端心跳。
- Control Plane：面向控制台、OpenAPI 和发布系统提供配置编辑、校验、发布、回滚、审计和版本管理。
- Storage Layer：PostgreSQL 保存配置对象、配置版本、发布记录、审计记录、灰度规则和客户端快照元数据。

推荐模块边界：

```text
stellnula-service
  api-http        HTTP bootstrap and admin APIs
  api-grpc        gRPC watch and delta delivery APIs
  application     use cases and orchestration
  domain          config model, scope, release, version
  infrastructure  PostgreSQL repository, cache, scheduler
  sdk-contract    DTO, proto, OpenAPI contract
```

### 2.2 Stellflux 选型

Stellnula Service 使用 Stellflux 的原因：

- `stellflux-spring-boot-starter-http-server`：暴露客户端首次 HTTP bootstrap 和管理面 OpenAPI。
- `stellflux-spring-boot-starter-grpc-server`：暴露 gRPC 长轮询订阅服务。
- `stellflux-spring-boot-starter-grpc-client`：后续服务间通信或 SDK 测试客户端可复用。
- `stellflux-spring-boot-starter-caffeine`：承载服务端全量配置内存缓存，并接入 logs、traces 和 metrics。
- `stellflux-spring-boot-starter-opentelemetry`、`stellflux-spring-boot-starter-metrics`、`stellflux-spring-boot-starter-traces`、`stellflux-spring-boot-starter-log`：提供生产可观测性。

第一阶段服务端不强依赖 `stellflux-spring-boot-starter-stellmap`。客户端首次通过域名访问 HTTP bootstrap，服务端在 bootstrap 响应中返回可用 HTTP / gRPC 地址列表、权重和 TTL。客户端再基于地址列表选择具体服务端节点，避免把配置中心自身的可用性绑定到外部注册中心。

PostgreSQL 是本服务的目标数据库。数据源实现以 PostgreSQL JDBC、连接池和迁移工具为准；如果后续 Stellflux datasource starter 已支持 PostgreSQL，再将数据源自动装配收敛到 Stellflux。

### 2.3 读写路径

写路径：

```text
Portal / OpenAPI
  -> permission check
  -> format and scope validation
  -> version generation
  -> PostgreSQL transaction
  -> release record
  -> cache refresh signal
  -> Config Service memory cache refresh
```

读路径：

```text
Client SDK
  -> HTTP bootstrap on first interaction
  -> Config Service memory cache
  -> local full snapshot response
  -> gRPC long polling with current revision
  -> async memory and local file update
```

服务端运行期不允许客户端查询直接访问 PostgreSQL。DB 异常时：

- 已启动节点继续使用内存缓存对外提供最后一次成功发布的配置。
- 写入、发布、回滚和需要强一致持久化的接口返回不可用。
- 定时刷新失败时记录 cache lag、db unavailable 和 last successful refresh time。
- 节点重启时仍然强依赖 PostgreSQL，不能仅靠旧内存状态启动。

### 2.4 服务端缓存模型

服务端每个节点维护可重建的全量缓存：

- `ConfigKey -> ActiveConfigSnapshot`
- `ScopeIndex -> ConfigKey list`
- `AppIndex -> ConfigKey list`
- `Revision -> ReleaseSnapshot`
- `ClientSubscription -> ClientWatchState`

缓存刷新策略：

- 启动时从 PostgreSQL 加载所有已发布配置和最新 revision。
- 定时扫描 `stn_config_release.revision` 或 `stn_change_event.id`。
- 发现新 revision 后按作用域重建受影响索引。
- 长轮询等待队列只依赖内存 revision，不访问 DB。
- 后续可以引入 Stellflow 或数据库通知机制减少轮询延迟。

### 2.5 客户端 SDK 模型

客户端 SDK 启动流程：

```text
1. Load local snapshot file if it exists.
2. Populate in-memory full config from local snapshot.
3. Call HTTP bootstrap and fetch full remote snapshot.
4. Replace in-memory full config with remote snapshot.
5. Persist remote snapshot to local file.
6. Start gRPC long polling with current revision.
```

客户端故障重启时，如果远端暂时不可用：

- 先读取本地文件恢复上一次成功配置。
- 按配置策略决定是否允许降级启动。
- 后台持续重试 HTTP bootstrap。
- 一旦远端恢复，强制拉取全量远端配置覆盖本地内存和本地文件。

运行态更新流程：

```text
gRPC Watch response
  -> validate revision and checksum
  -> fetch delta or full snapshot
  -> apply to in-memory full config
  -> persist local snapshot file asynchronously
  -> notify listeners
```

本地文件建议格式：

```text
${user.home}/.stellnula/${appId}/${env}/${cluster}/config-snapshot.json
```

### 2.6 作用域模型

配置对象的主分类只保留两类：

- `APPLICATION`：应用配置，归属于单个应用、服务或模块。
- `PUBLIC`：公共配置，被多个应用、多个客户端或多个服务共享。

其他维度作为配置属性存在：

- 环境：强隔离边界，例如 `dev`、`test`、`staging`、`prod`。
- 地域、可用区、集群：部署拓扑边界，同一环境内可按规则继承或复制。
- namespace、group：配置命名空间和业务分组。
- content type：`KV`、`FILE`。
- security level：普通配置、敏感配置。
- governance metadata：版本、发布、灰度、审批、审计和回滚点。

客户端读取优先级：

```text
env + region + zone + cluster
env + region + zone + default cluster
env + region + default zone + default cluster
env + default region + default zone + default cluster
env global default
```

回退只能发生在同一个 `env` 内，禁止跨环境继承、回退或自动复制。

## 3. Implementation

### 3.1 HTTP 接口设计

HTTP 用于首次交互、全量拉取、管理面操作和兼容非 gRPC 客户端。

#### 客户端首次启动

```http
POST /api/v1/client/bootstrap
Content-Type: application/json
```

Request:

```json
{
  "appId": "trade.order-service",
  "clientId": "trade-order-10.0.0.12-8080",
  "sdkVersion": "0.1.0",
  "env": "prod",
  "region": "cn-east-1",
  "zone": "cn-east-1a",
  "cluster": "default",
  "namespace": "application",
  "currentRevision": 0,
  "supportedTransports": ["grpc", "http"]
}
```

Response:

```json
{
  "serverTime": "2026-05-29T10:00:00Z",
  "revision": 1024,
  "snapshotChecksum": "sha256:8b2d...",
  "configs": [
    {
      "configKey": "server.port",
      "contentType": "KV",
      "value": "8080",
      "version": 12,
      "scope": {
        "env": "prod",
        "region": "cn-east-1",
        "zone": "cn-east-1a",
        "cluster": "default"
      }
    }
  ],
  "grpc": {
    "preferredTransport": "grpc",
    "watchTimeoutMillis": 30000,
    "heartbeatMillis": 10000
  },
  "servers": [
    {
      "serverId": "stellnula-a",
      "httpAddress": "https://10.0.0.11:8443",
      "grpcAddress": "10.0.0.11:9090",
      "weight": 100,
      "region": "cn-east-1",
      "zone": "cn-east-1a",
      "healthy": true
    },
    {
      "serverId": "stellnula-b",
      "httpAddress": "https://10.0.0.12:8443",
      "grpcAddress": "10.0.0.12:9090",
      "weight": 100,
      "region": "cn-east-1",
      "zone": "cn-east-1b",
      "healthy": true
    }
  ],
  "loadBalancing": {
    "strategy": "WEIGHTED_RENDEZVOUS_HASH",
    "hashKey": "appId:clientId:env:namespace",
    "failover": "NEXT_HEALTHY_CANDIDATE",
    "ttlSeconds": 60
  }
}
```

客户端默认使用加权 Rendezvous Hash 选择 gRPC 节点。该策略比普通随机更稳定，节点增减时迁移客户端更少；相比 P2C，它不要求 SDK 在第一阶段就维护每个服务端的实时 inflight、延迟和错误率指标。后续 SDK 具备足够观测数据后，可以在健康候选集中引入 P2C 作为增强策略。

#### 客户端全量拉取

```http
GET /api/v1/client/configs/full?appId=trade.order-service&env=prod&region=cn-east-1&zone=cn-east-1a&cluster=default&namespace=application
```

用途：

- HTTP bootstrap 失败重试后的全量补偿。
- gRPC delta 校验失败后的全量修复。
- 非 gRPC SDK 的兼容读取。

#### 客户端增量拉取

```http
GET /api/v1/client/configs/delta?appId=trade.order-service&env=prod&cluster=default&fromRevision=1000
```

用途：

- 客户端错过 gRPC 通知后补拉增量。
- 本地 revision 落后但未超过服务端 delta 保留窗口时减少传输量。

#### 客户端心跳

```http
POST /api/v1/client/heartbeat
Content-Type: application/json
```

用于上报 SDK 状态、本地 revision、最后成功更新时间和本地文件缓存状态。

#### 管理面配置接口

```http
POST   /api/v1/admin/configs
PUT    /api/v1/admin/configs/{configId}
GET    /api/v1/admin/configs/{configId}
GET    /api/v1/admin/configs?appId=&env=&namespace=&group=
POST   /api/v1/admin/configs/{configId}/versions
POST   /api/v1/admin/releases
POST   /api/v1/admin/releases/{releaseId}/rollback
GET    /api/v1/admin/releases?appId=&env=&namespace=
GET    /api/v1/admin/audit-logs?resourceType=&resourceId=
```

### 3.2 gRPC 接口设计

gRPC 用于首次 HTTP 之后的运行态长轮询。客户端带着当前 revision 调用 `Watch`，服务端在有新 revision 时立即返回；没有变化时阻塞到超时并返回 `NO_CHANGE`。

```proto
syntax = "proto3";

package stellnula.config.v1;

option java_multiple_files = true;
option java_package = "com.stellhub.stellnula.config.v1";

service StellnulaConfigService {
  rpc Watch(WatchRequest) returns (WatchResponse);
  rpc FetchFull(FetchFullRequest) returns (ConfigSnapshot);
  rpc FetchDelta(FetchDeltaRequest) returns (ConfigDelta);
  rpc ReportClientState(ClientStateRequest) returns (ClientStateResponse);
}

message ClientContext {
  string app_id = 1;
  string client_id = 2;
  string env = 3;
  string region = 4;
  string zone = 5;
  string cluster = 6;
  string namespace = 7;
}

message WatchRequest {
  ClientContext context = 1;
  int64 current_revision = 2;
  int32 timeout_millis = 3;
  string snapshot_checksum = 4;
}

message WatchResponse {
  WatchStatus status = 1;
  int64 latest_revision = 2;
  string latest_checksum = 3;
  bool full_sync_required = 4;
  repeated ConfigChange changes = 5;
}

enum WatchStatus {
  WATCH_STATUS_UNSPECIFIED = 0;
  CHANGED = 1;
  NO_CHANGE = 2;
  CLIENT_TOO_OLD = 3;
  UNAUTHORIZED = 4;
}

message FetchFullRequest {
  ClientContext context = 1;
}

message FetchDeltaRequest {
  ClientContext context = 1;
  int64 from_revision = 2;
}

message ConfigSnapshot {
  int64 revision = 1;
  string checksum = 2;
  repeated ConfigEntry entries = 3;
}

message ConfigDelta {
  int64 from_revision = 1;
  int64 to_revision = 2;
  string checksum = 3;
  repeated ConfigChange changes = 4;
}

message ConfigChange {
  ChangeType type = 1;
  ConfigEntry entry = 2;
}

enum ChangeType {
  CHANGE_TYPE_UNSPECIFIED = 0;
  UPSERT = 1;
  DELETE = 2;
}

message ConfigEntry {
  string config_key = 1;
  string content_type = 2;
  string value = 3;
  int64 version = 4;
  bool encrypted = 5;
}

message ClientStateRequest {
  ClientContext context = 1;
  int64 local_revision = 2;
  string local_checksum = 3;
  bool local_file_loaded = 4;
  string last_success_sync_time = 5;
}

message ClientStateResponse {
  bool accepted = 1;
  int64 server_revision = 2;
}
```

### 3.3 PostgreSQL 数据库设计

第一阶段表结构以 `ConfigDefinition`、`ConfigScope`、`ConfigRelease` 为核心，目标是“可发布、可审计、可回滚、可恢复缓存”。公共配置使用 `owner_type = 'PUBLIC'`，应用配置使用 `owner_type = 'APPLICATION'`。公共配置允许跨环境手动复制发布，但客户端读取时不允许跨环境回退。

```sql
create table stn_config_definition (
    id bigint generated always as identity primary key,
    config_id varchar(128) not null,
    config_name varchar(256) not null,
    owner_type varchar(32) not null,
    owner_id varchar(128) not null,
    namespace_code varchar(128) not null default 'default',
    group_code varchar(128) not null default 'default',
    content_type varchar(32) not null,
    sensitive boolean not null default false,
    description text,
    deleted boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_stn_config_definition_config_id unique (config_id),
    constraint ck_stn_config_definition_owner_type check (owner_type in ('APPLICATION', 'PUBLIC')),
    constraint ck_stn_config_definition_content_type check (content_type in ('KV', 'FILE'))
);

comment on table stn_config_definition is '配置定义表，保存应用配置和公共配置的逻辑定义';
comment on column stn_config_definition.id is '自增主键';
comment on column stn_config_definition.config_id is '配置全局唯一标识';
comment on column stn_config_definition.config_name is '配置名称';
comment on column stn_config_definition.owner_type is '配置归属类型，APPLICATION 表示应用配置，PUBLIC 表示公共配置';
comment on column stn_config_definition.owner_id is '配置归属标识，应用配置为 appId，公共配置为 publicNamespace';
comment on column stn_config_definition.namespace_code is '配置命名空间';
comment on column stn_config_definition.group_code is '配置分组';
comment on column stn_config_definition.content_type is '配置形态，KV 或 FILE';
comment on column stn_config_definition.sensitive is '是否为敏感配置';
comment on column stn_config_definition.description is '配置说明';
comment on column stn_config_definition.deleted is '是否逻辑删除';
comment on column stn_config_definition.created_at is '创建时间';
comment on column stn_config_definition.updated_at is '更新时间';

create table stn_config_scope (
    id bigint generated always as identity primary key,
    config_id varchar(128) not null references stn_config_definition (config_id),
    env varchar(64) not null,
    region varchar(64) not null default 'default',
    zone varchar(64) not null default 'default',
    cluster varchar(64) not null default 'default',
    scope_mode varchar(32) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_stn_config_scope unique (config_id, env, region, zone, cluster),
    constraint ck_stn_config_scope_mode check (scope_mode in ('EXACT', 'INHERITABLE', 'REPLICATED'))
);

comment on table stn_config_scope is '配置作用域表，描述配置在环境、地域、可用区和集群中的生效范围';
comment on column stn_config_scope.id is '自增主键';
comment on column stn_config_scope.config_id is '配置全局唯一标识';
comment on column stn_config_scope.env is '环境，强隔离边界';
comment on column stn_config_scope.region is '地域';
comment on column stn_config_scope.zone is '可用区';
comment on column stn_config_scope.cluster is '集群';
comment on column stn_config_scope.scope_mode is '作用域模式，EXACT 精确匹配，INHERITABLE 同环境可继承，REPLICATED 手动复制发布';
comment on column stn_config_scope.created_at is '创建时间';
comment on column stn_config_scope.updated_at is '更新时间';

create table stn_config_release (
    id bigint generated always as identity primary key,
    release_no varchar(64) not null,
    config_id varchar(128) not null references stn_config_definition (config_id),
    scope_id bigint not null references stn_config_scope (id),
    version bigint not null,
    revision bigint not null,
    content text not null,
    checksum varchar(128) not null,
    release_status varchar(32) not null,
    release_title varchar(256) not null,
    release_reason varchar(512) not null,
    created_by varchar(128) not null,
    released_at timestamptz not null default now(),
    rolled_back_from_release_no varchar(64),
    created_at timestamptz not null default now(),
    constraint uk_stn_config_release_no unique (release_no),
    constraint uk_stn_config_release_version unique (config_id, scope_id, version),
    constraint uk_stn_config_release_revision unique (revision),
    constraint ck_stn_config_release_status check (release_status in ('DRAFT', 'PUBLISHED', 'ROLLED_BACK', 'DELETED'))
);

comment on table stn_config_release is '配置发布表，保存某个配置在某个作用域下的版本内容和发布状态';
comment on column stn_config_release.id is '自增主键';
comment on column stn_config_release.release_no is '发布单号';
comment on column stn_config_release.config_id is '配置全局唯一标识';
comment on column stn_config_release.scope_id is '配置作用域主键';
comment on column stn_config_release.version is '配置版本号，在同一配置和作用域内递增';
comment on column stn_config_release.revision is '全局发布修订号，用于客户端增量同步和服务端缓存刷新';
comment on column stn_config_release.content is '配置内容，KV 保存值或 JSON，FILE 保存文件内容或文件引用';
comment on column stn_config_release.checksum is '配置内容校验值';
comment on column stn_config_release.release_status is '发布状态';
comment on column stn_config_release.release_title is '发布标题';
comment on column stn_config_release.release_reason is '发布原因';
comment on column stn_config_release.created_by is '创建人或发布人';
comment on column stn_config_release.released_at is '发布时间';
comment on column stn_config_release.rolled_back_from_release_no is '回滚来源发布单号';
comment on column stn_config_release.created_at is '创建时间';

create table stn_change_event (
    id bigint generated always as identity primary key,
    revision bigint not null,
    config_id varchar(128) not null references stn_config_definition (config_id),
    scope_id bigint not null references stn_config_scope (id),
    env varchar(64) not null,
    event_type varchar(32) not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    constraint ck_stn_change_event_type check (event_type in ('PUBLISHED', 'ROLLED_BACK', 'DELETED', 'COPIED'))
);

comment on table stn_change_event is '配置变更事件表，用于服务端缓存定时刷新和后续事件通知';
comment on column stn_change_event.id is '自增主键';
comment on column stn_change_event.revision is '全局发布修订号';
comment on column stn_change_event.config_id is '配置全局唯一标识';
comment on column stn_change_event.scope_id is '配置作用域主键';
comment on column stn_change_event.env is '事件所属环境';
comment on column stn_change_event.event_type is '事件类型';
comment on column stn_change_event.payload is '事件载荷';
comment on column stn_change_event.created_at is '创建时间';

create table stn_config_release_audit (
    id bigint generated always as identity primary key,
    release_no varchar(64) not null references stn_config_release (release_no),
    action varchar(64) not null,
    operator varchar(128) not null,
    request_id varchar(128),
    before_value jsonb,
    after_value jsonb,
    created_at timestamptz not null default now()
);

comment on table stn_config_release_audit is '配置发布审计表';
comment on column stn_config_release_audit.id is '自增主键';
comment on column stn_config_release_audit.release_no is '发布单号';
comment on column stn_config_release_audit.action is '审计动作';
comment on column stn_config_release_audit.operator is '操作人';
comment on column stn_config_release_audit.request_id is '请求链路标识';
comment on column stn_config_release_audit.before_value is '变更前内容';
comment on column stn_config_release_audit.after_value is '变更后内容';
comment on column stn_config_release_audit.created_at is '创建时间';

create table stn_client_snapshot (
    id bigint generated always as identity primary key,
    app_id varchar(128) not null,
    client_id varchar(256) not null,
    env varchar(64) not null,
    region varchar(64) not null,
    zone varchar(64) not null,
    cluster varchar(64) not null,
    namespace_code varchar(128) not null,
    local_revision bigint not null,
    local_checksum varchar(128) not null,
    local_file_loaded boolean not null default false,
    last_success_sync_at timestamptz,
    last_heartbeat_at timestamptz not null default now(),
    constraint uk_stn_client_snapshot unique (app_id, client_id, env, region, zone, cluster, namespace_code)
);

comment on table stn_client_snapshot is '客户端配置同步状态表';
comment on column stn_client_snapshot.id is '自增主键';
comment on column stn_client_snapshot.app_id is '客户端应用标识';
comment on column stn_client_snapshot.client_id is '客户端实例标识';
comment on column stn_client_snapshot.env is '客户端环境';
comment on column stn_client_snapshot.region is '客户端地域';
comment on column stn_client_snapshot.zone is '客户端可用区';
comment on column stn_client_snapshot.cluster is '客户端集群';
comment on column stn_client_snapshot.namespace_code is '客户端订阅命名空间';
comment on column stn_client_snapshot.local_revision is '客户端本地配置修订号';
comment on column stn_client_snapshot.local_checksum is '客户端本地配置校验值';
comment on column stn_client_snapshot.local_file_loaded is '客户端启动时是否加载本地文件';
comment on column stn_client_snapshot.last_success_sync_at is '客户端最后一次成功同步时间';
comment on column stn_client_snapshot.last_heartbeat_at is '客户端最后一次心跳时间';

create index idx_stn_config_definition_owner
    on stn_config_definition (owner_type, owner_id, namespace_code, group_code);

create index idx_stn_config_scope_lookup
    on stn_config_scope (env, region, zone, cluster);

create index idx_stn_config_release_lookup
    on stn_config_release (config_id, scope_id, release_status, revision);

create index idx_stn_change_event_revision
    on stn_change_event (revision);

create index idx_stn_client_snapshot_heartbeat
    on stn_client_snapshot (app_id, env, last_heartbeat_at);
```

### 3.4 缓存启动与刷新伪流程

服务端启动必须完成以下步骤后才能对外提供客户端读取：

```text
1. Connect PostgreSQL.
2. Load latest published releases from `stn_config_release`.
3. Load config definitions and scopes.
4. Build config definition index, scope index, owner index and revision index.
5. Mark cache status as READY.
6. Open HTTP and gRPC traffic.
```

定时刷新：

```text
1. Read max(revision) from `stn_config_release` or `stn_change_event`.
2. If remote revision <= local revision, skip.
3. Load changed releases by revision range.
4. Rebuild affected cache partitions.
5. Wake up gRPC watch waiters.
6. Record last successful refresh time.
```

### 3.5 客户端一致性规则

- 客户端内存永远保存一份完整配置快照。
- 本地文件永远保存最后一次校验成功的完整配置快照。
- 增量更新失败、checksum 不一致或 revision 间断时，客户端必须执行全量拉取。
- 本地文件只作为故障恢复来源，不作为服务端 truth source。
- 客户端通知业务 listener 前必须先更新本地内存。
- 本地文件写入可以异步，但必须使用临时文件加原子 rename，避免半文件。

## 4. Complete code

当前 README 提供第一批可落地契约：

- HTTP bootstrap、full、delta、heartbeat 和管理面接口。
- gRPC `Watch`、`FetchFull`、`FetchDelta`、`ReportClientState` 协议草案。
- PostgreSQL 第一批核心表结构。
- 服务端启动强 DB 依赖、运行时弱 DB 依赖和定时缓存刷新策略。
- 客户端 SDK 本地文件、本地全量内存、启动强制全量同步和故障重启降级策略。

补充设计文档：

- [Stellnula 架构设计](docs/architecture.md)
- [Stellnula 数据模型设计](docs/data-model.md)
- [HTTP 与 gRPC 混合协议选型](docs/protocol-selection.md)

后续代码落地时应优先按以下顺序推进：

1. 引入 Stellflux HTTP / gRPC server starter 和 PostgreSQL 连接配置。
2. 落地 Flyway 或 Liquibase 迁移脚本。
3. 实现服务端启动缓存构建。
4. 实现 HTTP bootstrap 和全量读取。
5. 实现 gRPC 长轮询 watch。
6. 实现客户端 SDK 本地文件缓存和异步更新。
7. 接入 metrics、traces、logs 和 cache lag 告警。
