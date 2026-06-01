create table if not exists stn_config_definition (
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

create table if not exists stn_config_scope (
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

create table if not exists stn_config_release (
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

create table if not exists stn_config_gray_rule (
    id bigint generated always as identity primary key,
    config_id varchar(128) not null references stn_config_definition (config_id),
    scope_id bigint not null references stn_config_scope (id),
    gray_name varchar(256) not null,
    rule_type varchar(32) not null,
    gray_rules jsonb not null,
    config_value text not null,
    gray_version bigint not null,
    effective_revision bigint not null,
    checksum varchar(128) not null,
    priority integer not null default 100,
    status varchar(32) not null,
    start_time timestamptz,
    end_time timestamptz,
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_stn_config_gray_rule_name unique (config_id, scope_id, gray_name),
    constraint uk_stn_config_gray_rule_revision unique (effective_revision),
    constraint ck_stn_config_gray_rule_type check (rule_type in ('IP', 'TAG', 'PERCENTAGE', 'COMPOSITE')),
    constraint ck_stn_config_gray_rule_status check (status in ('DRAFT', 'ACTIVE', 'ENDED'))
);

comment on table stn_config_gray_rule is '配置灰度规则表，保存一主多灰模型下的灰度配置内容、路由规则、状态和有效修订号';
comment on column stn_config_gray_rule.id is '自增主键';
comment on column stn_config_gray_rule.config_id is '配置全局唯一标识，关联主配置定义';
comment on column stn_config_gray_rule.scope_id is '配置作用域主键，灰度规则必须依附具体作用域';
comment on column stn_config_gray_rule.gray_name is '灰度策略名称';
comment on column stn_config_gray_rule.rule_type is '灰度规则主类型，IP、TAG、PERCENTAGE 或 COMPOSITE';
comment on column stn_config_gray_rule.gray_rules is '灰度路由规则 JSON，支持机器标识、标签、比例和组合表达式';
comment on column stn_config_gray_rule.config_value is '灰度命中后返回的配置内容';
comment on column stn_config_gray_rule.gray_version is '灰度配置版本号，在同一灰度规则内递增';
comment on column stn_config_gray_rule.effective_revision is '有效路由修订号，灰度规则、灰度状态或灰度内容变化时递增';
comment on column stn_config_gray_rule.checksum is '灰度配置内容校验值';
comment on column stn_config_gray_rule.priority is '灰度优先级，数值越小优先级越高';
comment on column stn_config_gray_rule.status is '灰度状态，DRAFT 草稿，ACTIVE 生效，ENDED 已结束';
comment on column stn_config_gray_rule.start_time is '灰度开始时间';
comment on column stn_config_gray_rule.end_time is '灰度结束时间';
comment on column stn_config_gray_rule.created_by is '创建人';
comment on column stn_config_gray_rule.updated_by is '最后更新人';
comment on column stn_config_gray_rule.created_at is '创建时间';
comment on column stn_config_gray_rule.updated_at is '更新时间';

create table if not exists stn_config_revision (
    revision bigint generated always as identity primary key,
    revision_type varchar(64) not null,
    config_id varchar(128) references stn_config_definition (config_id),
    scope_id bigint references stn_config_scope (id),
    source_table varchar(128),
    source_id bigint,
    event_type varchar(64) not null,
    revision_reason varchar(512),
    payload jsonb not null default '{}'::jsonb,
    created_by varchar(128) not null,
    created_at timestamptz not null default now(),
    constraint ck_stn_config_revision_type check (revision_type in ('BASE_RELEASE', 'GRAY_ROUTE', 'DELETE', 'COPY', 'ROLLBACK', 'SYSTEM')),
    constraint ck_stn_config_revision_event_type check (event_type in ('PUBLISHED', 'ROLLED_BACK', 'DELETED', 'COPIED', 'GRAY_CREATED', 'GRAY_PUBLISHED', 'GRAY_RULE_CHANGED', 'GRAY_ROLLED_BACK', 'GRAY_FULL_RELEASE', 'GRAY_ENDED', 'CACHE_REBUILD'))
);

comment on table stn_config_revision is '配置全局修订号表，统一生成和记录主配置、灰度路由、删除、复制、回滚等会影响客户端可见结果的 revision';
comment on column stn_config_revision.revision is '全局单调递增修订号';
comment on column stn_config_revision.revision_type is '修订类型，区分主配置发布、灰度路由变化、删除、复制、回滚和系统事件';
comment on column stn_config_revision.config_id is '配置全局唯一标识，系统级 revision 可为空';
comment on column stn_config_revision.scope_id is '配置作用域主键，系统级 revision 可为空';
comment on column stn_config_revision.source_table is '产生该 revision 的来源表名';
comment on column stn_config_revision.source_id is '产生该 revision 的来源记录主键';
comment on column stn_config_revision.event_type is '修订事件类型，用于客户端增量同步和缓存刷新判断';
comment on column stn_config_revision.revision_reason is '修订原因或发布说明';
comment on column stn_config_revision.payload is '修订事件载荷，保存变更摘要、影响范围或灰度规则快照';
comment on column stn_config_revision.created_by is '创建人或系统节点';
comment on column stn_config_revision.created_at is '创建时间';

create table if not exists stn_change_event (
    id bigint generated always as identity primary key,
    revision bigint not null,
    config_id varchar(128) not null references stn_config_definition (config_id),
    scope_id bigint not null references stn_config_scope (id),
    env varchar(64) not null,
    event_type varchar(32) not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    constraint ck_stn_change_event_type check (event_type in ('PUBLISHED', 'ROLLED_BACK', 'DELETED', 'COPIED', 'GRAY_CREATED', 'GRAY_PUBLISHED', 'GRAY_RULE_CHANGED', 'GRAY_ROLLED_BACK', 'GRAY_FULL_RELEASE', 'GRAY_ENDED'))
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

create table if not exists stn_config_release_audit (
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

create table if not exists stn_config_release_history (
    id bigint generated always as identity primary key,
    config_id varchar(128) not null references stn_config_definition (config_id),
    scope_id bigint not null references stn_config_scope (id),
    release_type varchar(64) not null,
    gray_rule_id bigint references stn_config_gray_rule (id),
    before_value text,
    after_value text,
    before_revision bigint,
    after_revision bigint not null,
    operator varchar(128) not null,
    operation_reason varchar(512),
    created_at timestamptz not null default now(),
    constraint ck_stn_config_release_history_type check (release_type in ('BASE_PUBLISH', 'BASE_DELETE', 'GRAY_CREATE', 'GRAY_RULE_UPDATE', 'GRAY_PUBLISH', 'GRAY_ROLLBACK', 'GRAY_FULL_RELEASE', 'GRAY_END', 'BASE_ROLLBACK'))
);

comment on table stn_config_release_history is '配置发布历史表，统一记录主配置发布、灰度发布、灰度回滚、全量推开和灰度结束';
comment on column stn_config_release_history.id is '自增主键';
comment on column stn_config_release_history.config_id is '配置全局唯一标识';
comment on column stn_config_release_history.scope_id is '配置作用域主键';
comment on column stn_config_release_history.release_type is '发布历史类型';
comment on column stn_config_release_history.gray_rule_id is '关联灰度规则主键，主配置发布时为空';
comment on column stn_config_release_history.before_value is '变更前配置内容';
comment on column stn_config_release_history.after_value is '变更后配置内容';
comment on column stn_config_release_history.before_revision is '变更前有效修订号';
comment on column stn_config_release_history.after_revision is '变更后有效修订号';
comment on column stn_config_release_history.operator is '操作人';
comment on column stn_config_release_history.operation_reason is '操作原因';
comment on column stn_config_release_history.created_at is '创建时间';

create table if not exists stn_data_plane_node (
    id bigint generated always as identity primary key,
    server_id varchar(128) not null,
    http_address varchar(512) not null,
    grpc_address varchar(512) not null,
    region varchar(64) not null default 'default',
    zone varchar(64) not null default 'default',
    weight integer not null default 100,
    status varchar(32) not null default 'ACTIVE',
    healthy boolean not null default true,
    active_watch_count integer not null default 0,
    load_score numeric(10, 4) not null default 0,
    failure_count integer not null default 0,
    metadata jsonb not null default '{}'::jsonb,
    last_probe_at timestamptz,
    drain_started_at timestamptz,
    offline_at timestamptz,
    last_heartbeat_at timestamptz,
    registered_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_stn_data_plane_node_server_id unique (server_id),
    constraint ck_stn_data_plane_node_weight check (weight > 0),
    constraint ck_stn_data_plane_node_active_watch check (active_watch_count >= 0),
    constraint ck_stn_data_plane_node_load_score check (load_score >= 0),
    constraint ck_stn_data_plane_node_failure_count check (failure_count >= 0),
    constraint ck_stn_data_plane_node_status check (status in ('ACTIVE', 'DRAINING', 'OFFLINE'))
);

comment on table stn_data_plane_node is '配置中心数据面节点表，用于客户端 bootstrap 返回可用 HTTP 和 gRPC 地址列表';
comment on column stn_data_plane_node.id is '自增主键';
comment on column stn_data_plane_node.server_id is '数据面节点唯一标识';
comment on column stn_data_plane_node.http_address is '节点 HTTP 访问地址，用于客户端首次拉取和全量同步';
comment on column stn_data_plane_node.grpc_address is '节点 gRPC 访问地址，用于客户端长轮询和增量同步';
comment on column stn_data_plane_node.region is '节点所属地域';
comment on column stn_data_plane_node.zone is '节点所属可用区';
comment on column stn_data_plane_node.weight is '节点负载均衡权重';
comment on column stn_data_plane_node.status is '节点状态，ACTIVE 可用，DRAINING 排空中，OFFLINE 下线';
comment on column stn_data_plane_node.healthy is '节点健康状态';
comment on column stn_data_plane_node.active_watch_count is '节点当前活跃 watch 请求数量，用于负载观测和路由排序';
comment on column stn_data_plane_node.load_score is '节点负载评分，数值越低越适合新客户端选择';
comment on column stn_data_plane_node.failure_count is '节点连续健康探测失败次数，超过阈值后自动剔除';
comment on column stn_data_plane_node.metadata is '节点扩展元数据';
comment on column stn_data_plane_node.last_probe_at is '节点最后一次健康探测时间';
comment on column stn_data_plane_node.drain_started_at is '节点进入 DRAINING 排空状态的时间';
comment on column stn_data_plane_node.offline_at is '节点进入 OFFLINE 下线状态的时间';
comment on column stn_data_plane_node.last_heartbeat_at is '节点最后一次心跳时间';
comment on column stn_data_plane_node.registered_at is '节点注册时间';
comment on column stn_data_plane_node.updated_at is '节点更新时间';

create table if not exists stn_client_instance (
    id bigint generated always as identity primary key,
    app_id varchar(128) not null,
    client_id varchar(256) not null,
    env varchar(64) not null,
    region varchar(64) not null default 'default',
    zone varchar(64) not null default 'default',
    cluster varchar(64) not null default 'default',
    namespace_code varchar(128) not null default 'default',
    client_ip varchar(64),
    host_name varchar(256),
    sdk_version varchar(64),
    labels jsonb not null default '{}'::jsonb,
    metadata jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'ONLINE',
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_stn_client_instance unique (app_id, client_id, env, region, zone, cluster, namespace_code),
    constraint ck_stn_client_instance_status check (status in ('ONLINE', 'OFFLINE', 'UNKNOWN'))
);

comment on table stn_client_instance is '客户端实例表，保存客户端稳定上下文、IP 和标签，用于灰度路由恢复、命中排查和实例观测';
comment on column stn_client_instance.id is '自增主键';
comment on column stn_client_instance.app_id is '客户端应用标识';
comment on column stn_client_instance.client_id is '客户端实例标识';
comment on column stn_client_instance.env is '客户端环境';
comment on column stn_client_instance.region is '客户端地域';
comment on column stn_client_instance.zone is '客户端可用区';
comment on column stn_client_instance.cluster is '客户端集群';
comment on column stn_client_instance.namespace_code is '客户端默认命名空间';
comment on column stn_client_instance.client_ip is '客户端 IP 地址';
comment on column stn_client_instance.host_name is '客户端主机名';
comment on column stn_client_instance.sdk_version is '客户端 SDK 版本';
comment on column stn_client_instance.labels is '客户端标签集合，用于标签灰度和影响面分析';
comment on column stn_client_instance.metadata is '客户端扩展元数据';
comment on column stn_client_instance.status is '客户端状态，ONLINE 在线，OFFLINE 离线，UNKNOWN 未知';
comment on column stn_client_instance.first_seen_at is '客户端首次出现时间';
comment on column stn_client_instance.last_seen_at is '客户端最后一次出现或心跳时间';
comment on column stn_client_instance.updated_at is '客户端实例记录更新时间';

create table if not exists stn_client_subscription (
    id bigint generated always as identity primary key,
    app_id varchar(128) not null,
    client_id varchar(256) not null,
    env varchar(64) not null,
    region varchar(64) not null default 'default',
    zone varchar(64) not null default 'default',
    cluster varchar(64) not null default 'default',
    namespace_code varchar(128) not null default 'default',
    group_code varchar(128) not null default 'default',
    subscription_type varchar(32) not null,
    subscription_key varchar(256) not null,
    current_revision bigint not null default 0,
    current_checksum varchar(128) not null default '',
    transport varchar(32) not null default 'GRPC',
    status varchar(32) not null default 'ACTIVE',
    subscribed_at timestamptz not null default now(),
    last_watch_at timestamptz,
    updated_at timestamptz not null default now(),
    constraint uk_stn_client_subscription unique (app_id, client_id, env, region, zone, cluster, namespace_code, group_code, subscription_type, subscription_key),
    constraint ck_stn_client_subscription_type check (subscription_type in ('CONFIG', 'PUBLIC_CONFIG', 'GOVERNANCE_RULE', 'ALL')),
    constraint ck_stn_client_subscription_transport check (transport in ('HTTP', 'GRPC')),
    constraint ck_stn_client_subscription_status check (status in ('ACTIVE', 'PAUSED', 'CANCELLED'))
);

comment on table stn_client_subscription is '客户端订阅表，记录客户端订阅的配置、公共配置或治理规则范围，用于 watch 恢复、影响面分析和增量同步定位';
comment on column stn_client_subscription.id is '自增主键';
comment on column stn_client_subscription.app_id is '客户端应用标识';
comment on column stn_client_subscription.client_id is '客户端实例标识';
comment on column stn_client_subscription.env is '订阅环境';
comment on column stn_client_subscription.region is '订阅地域';
comment on column stn_client_subscription.zone is '订阅可用区';
comment on column stn_client_subscription.cluster is '订阅集群';
comment on column stn_client_subscription.namespace_code is '订阅命名空间';
comment on column stn_client_subscription.group_code is '订阅分组';
comment on column stn_client_subscription.subscription_type is '订阅类型，CONFIG 应用配置，PUBLIC_CONFIG 公共配置，GOVERNANCE_RULE 治理规则，ALL 全量订阅';
comment on column stn_client_subscription.subscription_key is '订阅键，通常为 configId，也可使用 * 表示当前范围下全部配置';
comment on column stn_client_subscription.current_revision is '客户端当前已知 revision';
comment on column stn_client_subscription.current_checksum is '客户端当前已知配置快照校验值';
comment on column stn_client_subscription.transport is '订阅使用的传输协议，HTTP 或 GRPC';
comment on column stn_client_subscription.status is '订阅状态，ACTIVE 生效，PAUSED 暂停，CANCELLED 取消';
comment on column stn_client_subscription.subscribed_at is '首次订阅时间';
comment on column stn_client_subscription.last_watch_at is '最后一次 watch 或拉取时间';
comment on column stn_client_subscription.updated_at is '订阅记录更新时间';

create table if not exists stn_client_snapshot (
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

create index if not exists idx_stn_config_definition_owner
    on stn_config_definition (owner_type, owner_id, namespace_code, group_code);

create index if not exists idx_stn_config_scope_lookup
    on stn_config_scope (env, region, zone, cluster);

create index if not exists idx_stn_config_release_lookup
    on stn_config_release (config_id, scope_id, release_status, revision);

create index if not exists idx_stn_config_gray_rule_dimension
    on stn_config_gray_rule (config_id, scope_id);

create index if not exists idx_stn_config_gray_rule_route
    on stn_config_gray_rule (config_id, scope_id, status, priority);

create index if not exists idx_stn_config_gray_rule_revision
    on stn_config_gray_rule (effective_revision);

create index if not exists idx_stn_config_revision_config
    on stn_config_revision (config_id, scope_id, revision);

create index if not exists idx_stn_config_revision_created
    on stn_config_revision (created_at);

create index if not exists idx_stn_change_event_revision
    on stn_change_event (revision);

create index if not exists idx_stn_config_release_history_lookup
    on stn_config_release_history (config_id, scope_id, created_at);

create index if not exists idx_stn_data_plane_node_route
    on stn_data_plane_node (status, healthy, region, zone, weight);

create index if not exists idx_stn_data_plane_node_heartbeat
    on stn_data_plane_node (last_heartbeat_at);

create index if not exists idx_stn_data_plane_node_load
    on stn_data_plane_node (status, healthy, load_score, active_watch_count);

create index if not exists idx_stn_client_instance_lookup
    on stn_client_instance (app_id, env, region, zone, cluster, last_seen_at);

create index if not exists idx_stn_client_instance_labels
    on stn_client_instance using gin (labels);

create index if not exists idx_stn_client_subscription_lookup
    on stn_client_subscription (app_id, env, namespace_code, group_code, subscription_type, subscription_key);

create index if not exists idx_stn_client_subscription_watch
    on stn_client_subscription (status, current_revision, last_watch_at);

create index if not exists idx_stn_client_snapshot_heartbeat
    on stn_client_snapshot (app_id, env, last_heartbeat_at);
