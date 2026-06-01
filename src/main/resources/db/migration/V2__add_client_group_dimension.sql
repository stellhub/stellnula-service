alter table stn_client_instance
    add column if not exists group_code varchar(128) not null default 'default';

comment on column stn_client_instance.group_code is '客户端默认分组';

alter table stn_client_instance
    drop constraint if exists uk_stn_client_instance;

alter table stn_client_instance
    add constraint uk_stn_client_instance unique (
        app_id,
        client_id,
        env,
        region,
        zone,
        cluster,
        namespace_code,
        group_code
    );

drop index if exists idx_stn_client_instance_lookup;

create index if not exists idx_stn_client_instance_lookup
    on stn_client_instance (app_id, env, namespace_code, group_code, region, zone, cluster, last_seen_at);

alter table stn_client_snapshot
    add column if not exists group_code varchar(128) not null default 'default';

comment on column stn_client_snapshot.group_code is '客户端订阅分组';

alter table stn_client_snapshot
    drop constraint if exists uk_stn_client_snapshot;

alter table stn_client_snapshot
    add constraint uk_stn_client_snapshot unique (
        app_id,
        client_id,
        env,
        region,
        zone,
        cluster,
        namespace_code,
        group_code
    );

drop index if exists idx_stn_client_snapshot_heartbeat;

create index if not exists idx_stn_client_snapshot_heartbeat
    on stn_client_snapshot (app_id, env, namespace_code, group_code, last_heartbeat_at);
