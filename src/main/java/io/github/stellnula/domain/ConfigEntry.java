package io.github.stellnula.domain;

public record ConfigEntry(
    String configId,
    String configKey,
    String ownerType,
    String ownerId,
    String namespaceCode,
    String groupCode,
    String contentType,
    String value,
    long version,
    long revision,
    boolean encrypted,
    ConfigScope scope,
    boolean deleted,
    String matchedType,
    Long matchedGrayId,
    String matchedGrayName,
    Long grayVersion) {

  public ConfigEntry(
      String configId,
      String configKey,
      String ownerType,
      String ownerId,
      String namespaceCode,
      String groupCode,
      String contentType,
      String value,
      long version,
      long revision,
      boolean encrypted,
      ConfigScope scope) {
    this(
        configId,
        configKey,
        ownerType,
        ownerId,
        namespaceCode,
        groupCode,
        contentType,
        value,
        version,
        revision,
        encrypted,
        scope,
        false,
        "BASE",
        null,
        null,
        null);
  }

  public ConfigEntry(
      String configId,
      String configKey,
      String ownerType,
      String ownerId,
      String namespaceCode,
      String groupCode,
      String contentType,
      String value,
      long version,
      long revision,
      boolean encrypted,
      ConfigScope scope,
      boolean deleted) {
    this(
        configId,
        configKey,
        ownerType,
        ownerId,
        namespaceCode,
        groupCode,
        contentType,
        value,
        version,
        revision,
        encrypted,
        scope,
        deleted,
        "BASE",
        null,
        null,
        null);
  }

  public ConfigEntry withGrayRule(ConfigGrayRule rule) {
    return new ConfigEntry(
        configId,
        configKey,
        ownerType,
        ownerId,
        namespaceCode,
        groupCode,
        contentType,
        rule.configValue(),
        rule.grayVersion(),
        rule.effectiveRevision(),
        encrypted,
        scope,
        false,
        "GRAY",
        rule.id(),
        rule.grayName(),
        rule.grayVersion());
  }

  public ConfigEntry withValue(String newValue) {
    return new ConfigEntry(
        configId,
        configKey,
        ownerType,
        ownerId,
        namespaceCode,
        groupCode,
        contentType,
        newValue,
        version,
        revision,
        encrypted,
        scope,
        deleted,
        matchedType,
        matchedGrayId,
        matchedGrayName,
        grayVersion);
  }
}
