create table if not exists stn_governance_rule_index (
    id bigint generated always as identity primary key,
    config_id varchar(128) not null references stn_config_definition (config_id),
    scope_id bigint not null references stn_config_scope (id),
    release_id bigint not null references stn_config_release (id),
    revision bigint not null,
    owner_type varchar(32) not null,
    owner_id varchar(128) not null,
    env varchar(64) not null,
    region varchar(64) not null default 'default',
    zone varchar(64) not null default 'default',
    cluster varchar(64) not null default 'default',
    rule_type varchar(32) not null,
    target_service varchar(256) not null,
    status varchar(32) not null,
    priority integer not null default 100,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_stn_governance_rule_index_release unique (release_id),
    constraint ck_stn_governance_rule_index_rule_type check (rule_type in ('ROUTE', 'RATE_LIMIT', 'CIRCUIT_BREAKER', 'DEGRADE')),
    constraint ck_stn_governance_rule_index_status check (status in ('DRAFT', 'ACTIVE', 'DISABLED'))
);

comment on table stn_governance_rule_index is '服务治理规则查询索引表，将治理规则 JSON 中的核心查询字段结构化保存';
comment on column stn_governance_rule_index.id is '自增主键';
comment on column stn_governance_rule_index.config_id is '治理规则配置全局唯一标识';
comment on column stn_governance_rule_index.scope_id is '治理规则作用域主键';
comment on column stn_governance_rule_index.release_id is '关联配置发布记录主键';
comment on column stn_governance_rule_index.revision is '关联发布修订号';
comment on column stn_governance_rule_index.owner_type is '治理规则归属类型';
comment on column stn_governance_rule_index.owner_id is '治理规则归属标识，通常为应用 appId';
comment on column stn_governance_rule_index.env is '治理规则环境';
comment on column stn_governance_rule_index.region is '治理规则地域';
comment on column stn_governance_rule_index.zone is '治理规则可用区';
comment on column stn_governance_rule_index.cluster is '治理规则集群';
comment on column stn_governance_rule_index.rule_type is '治理规则类型，ROUTE 路由，RATE_LIMIT 限流，CIRCUIT_BREAKER 熔断，DEGRADE 降级';
comment on column stn_governance_rule_index.target_service is '治理规则作用目标服务';
comment on column stn_governance_rule_index.status is '治理规则状态，DRAFT 草稿，ACTIVE 生效，DISABLED 禁用';
comment on column stn_governance_rule_index.priority is '治理规则优先级，数值越小优先级越高';
comment on column stn_governance_rule_index.created_at is '创建时间';
comment on column stn_governance_rule_index.updated_at is '更新时间';

create index if not exists idx_stn_governance_rule_query
    on stn_governance_rule_index (env, owner_id, rule_type, target_service, status, priority);

create index if not exists idx_stn_governance_rule_target
    on stn_governance_rule_index (target_service, status, priority);

create index if not exists idx_stn_governance_rule_revision
    on stn_governance_rule_index (revision);

insert into stn_governance_rule_index (
    config_id,
    scope_id,
    release_id,
    revision,
    owner_type,
    owner_id,
    env,
    region,
    zone,
    cluster,
    rule_type,
    target_service,
    status,
    priority
)
select d.config_id,
       s.id as scope_id,
       r.id as release_id,
       r.revision,
       d.owner_type,
       d.owner_id,
       s.env,
       s.region,
       s.zone,
       s.cluster,
       upper(r.content::jsonb ->> 'ruleType') as rule_type,
       r.content::jsonb ->> 'targetService' as target_service,
       upper(r.content::jsonb ->> 'status') as status,
       (r.content::jsonb ->> 'priority')::integer as priority
  from stn_config_release r
  join stn_config_definition d
    on d.config_id = r.config_id
  join stn_config_scope s
    on s.id = r.scope_id
 where d.namespace_code = 'governance'
   and d.group_code = 'service-governance'
   and r.release_status = 'PUBLISHED'
   and d.deleted = false
   and r.content <> ''
on conflict (release_id)
do nothing;
