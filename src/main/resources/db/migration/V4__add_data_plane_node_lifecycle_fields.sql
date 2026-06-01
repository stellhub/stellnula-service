alter table stn_data_plane_node
    add column if not exists active_watch_count integer not null default 0,
    add column if not exists load_score numeric(10, 4) not null default 0,
    add column if not exists failure_count integer not null default 0,
    add column if not exists last_probe_at timestamptz,
    add column if not exists drain_started_at timestamptz,
    add column if not exists offline_at timestamptz;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'ck_stn_data_plane_node_active_watch'
    ) then
        alter table stn_data_plane_node
            add constraint ck_stn_data_plane_node_active_watch check (active_watch_count >= 0);
    end if;
    if not exists (
        select 1 from pg_constraint where conname = 'ck_stn_data_plane_node_load_score'
    ) then
        alter table stn_data_plane_node
            add constraint ck_stn_data_plane_node_load_score check (load_score >= 0);
    end if;
    if not exists (
        select 1 from pg_constraint where conname = 'ck_stn_data_plane_node_failure_count'
    ) then
        alter table stn_data_plane_node
            add constraint ck_stn_data_plane_node_failure_count check (failure_count >= 0);
    end if;
end $$;

comment on column stn_data_plane_node.active_watch_count is '节点当前活跃 watch 请求数量，用于负载观测和路由排序';
comment on column stn_data_plane_node.load_score is '节点负载评分，数值越低越适合新客户端选择';
comment on column stn_data_plane_node.failure_count is '节点连续健康探测失败次数，超过阈值后自动剔除';
comment on column stn_data_plane_node.last_probe_at is '节点最后一次健康探测时间';
comment on column stn_data_plane_node.drain_started_at is '节点进入 DRAINING 排空状态的时间';
comment on column stn_data_plane_node.offline_at is '节点进入 OFFLINE 下线状态的时间';

create index if not exists idx_stn_data_plane_node_load
    on stn_data_plane_node (status, healthy, load_score, active_watch_count);
