package io.github.stellnula.repository;

import io.github.stellnula.domain.ConfigEntry;
import io.github.stellnula.domain.ConfigGrayRule;
import java.util.List;

public interface ConfigReleaseRepository {

  /** 加载所有已发布配置，用于服务启动和缓存重建。 */
  List<ConfigEntry> loadLatestPublishedEntries();

  /** 加载客户端可见的灰度规则事件，用于服务端内存路由和增量同步。 */
  List<ConfigGrayRule> loadClientVisibleGrayRules();

  /** 加载最近的基线发布和删除事件，用于内存增量事件窗口。 */
  List<ConfigEntry> loadRecentReleaseEvents(int limit);

  /** 加载指定 revision 之后的基线发布和删除事件，用于运行时增量刷新。 */
  List<ConfigEntry> loadReleaseEventsAfter(long revision, int limit);

  /** 加载指定 revision 之后的变更事件 revision，用于判断增量窗口是否完整。 */
  List<Long> loadChangeEventRevisionsAfter(long revision, int limit);

  /** 加载最近的灰度变更事件，用于内存增量事件窗口。 */
  List<ConfigGrayRule> loadRecentGrayRuleEvents(int limit);

  /** 加载指定 revision 之后的灰度变更事件，用于运行时增量刷新。 */
  List<ConfigGrayRule> loadGrayRuleEventsAfter(long revision, int limit);

  /** 查询持久化层最新 revision。 */
  long findMaxRevision();

  /** 保存客户端同步状态，作为观测数据而非读取路径依赖。 */
  void upsertClientSnapshot(ClientSnapshotState state);
}
