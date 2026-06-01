package io.github.stellnula.repository;

public interface ClientDataPlaneRepository {

  /** 保存客户端实例上下文，供灰度路由恢复和实例观测使用。 */
  void upsertClientInstance(ClientInstanceState state);

  /** 保存客户端订阅范围和当前 revision。 */
  void upsertClientSubscription(ClientSubscriptionState state);
}
