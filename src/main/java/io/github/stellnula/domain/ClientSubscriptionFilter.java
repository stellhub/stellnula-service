package io.github.stellnula.domain;

public record ClientSubscriptionFilter(
    String groupCode, String subscriptionType, String subscriptionKey) {

  private static final String DEFAULT_GROUP = "default";
  private static final String WILDCARD = "*";

  public ClientSubscriptionFilter normalize() {
    return new ClientSubscriptionFilter(
        defaultText(groupCode, DEFAULT_GROUP),
        normalizeSubscriptionType(subscriptionType),
        defaultText(subscriptionKey, WILDCARD));
  }

  /** 判断配置是否匹配当前订阅过滤器。 */
  public boolean matches(ConfigEntry entry) {
    ClientSubscriptionFilter filter = normalize();
    if (!WILDCARD.equals(filter.groupCode()) && !filter.groupCode().equals(entry.groupCode())) {
      return false;
    }
    if (!WILDCARD.equals(filter.subscriptionKey())
        && !filter.subscriptionKey().equals(entry.configId())) {
      return false;
    }
    return switch (filter.subscriptionType()) {
      case "CONFIG" -> "APPLICATION".equals(entry.ownerType()) && !isGovernanceRule(entry);
      case "PUBLIC_CONFIG" -> "PUBLIC".equals(entry.ownerType());
      case "GOVERNANCE_RULE" -> isGovernanceRule(entry);
      case "ALL" -> true;
      default -> false;
    };
  }

  private static boolean isGovernanceRule(ConfigEntry entry) {
    return "governance".equals(entry.namespaceCode())
        || "service-governance".equals(entry.groupCode());
  }

  private static String normalizeSubscriptionType(String value) {
    String resolved = defaultText(value, "ALL").toUpperCase();
    return switch (resolved) {
      case "CONFIG", "PUBLIC_CONFIG", "GOVERNANCE_RULE", "ALL" -> resolved;
      default -> throw new IllegalArgumentException("subscriptionType is not supported: " + value);
    };
  }

  private static String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
