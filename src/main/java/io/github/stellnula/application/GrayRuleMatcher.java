package io.github.stellnula.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stellnula.domain.ClientContext;
import io.github.stellnula.domain.ConfigGrayRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GrayRuleMatcher {

  private final ObjectMapper objectMapper;

  /** 判断灰度规则当前是否处于可生效时间窗口。 */
  public boolean isEffective(ConfigGrayRule rule, OffsetDateTime now) {
    return isEffective(rule.status(), rule.startTime(), rule.endTime(), now);
  }

  /** 判断灰度状态和时间窗口是否允许生效。 */
  public boolean isEffective(
      String status, OffsetDateTime startTime, OffsetDateTime endTime, OffsetDateTime now) {
    if (!"ACTIVE".equals(status)) {
      return false;
    }
    if (startTime != null && now.isBefore(startTime)) {
      return false;
    }
    return endTime == null || now.isBefore(endTime);
  }

  /** 判断客户端上下文是否命中灰度规则。 */
  public boolean matches(ClientContext context, ConfigGrayRule rule, OffsetDateTime now) {
    return isEffective(rule, now) && matchesRules(context, rule.grayRules());
  }

  /** 判断客户端上下文是否命中灰度规则表达式。 */
  public boolean matchesRules(ClientContext context, String grayRules) {
    try {
      return matchesNode(context, objectMapper.readTree(grayRules));
    } catch (RuntimeException ex) {
      return false;
    } catch (Exception ex) {
      return false;
    }
  }

  private boolean matchesNode(ClientContext context, JsonNode node) {
    String type = node.path("type").asText("");
    String op = node.path("op").asText("");
    return switch (type) {
      case "IP" -> matchesIp(context.clientIp(), op, node.path("values"));
      case "TAG" -> matchesTag(context.labels(), op, node);
      case "PERCENTAGE" -> matchesPercentage(context, node);
      case "COMPOSITE" -> matchesComposite(context, op, node.path("conditions"));
      default -> false;
    };
  }

  private boolean matchesIp(String clientIp, String op, JsonNode values) {
    if (clientIp == null || clientIp.isBlank() || !values.isArray()) {
      return false;
    }
    for (JsonNode value : values) {
      String candidate = value.asText();
      if ("IN".equals(op) && clientIp.equals(candidate)) {
        return true;
      }
      if ("CIDR".equals(op) && matchesCidr(clientIp, candidate)) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesTag(Map<String, String> labels, String op, JsonNode node) {
    if ("EQ".equals(op)) {
      return node.path("value").asText("").equals(labels.get(node.path("key").asText("")));
    }
    JsonNode values = node.path("values");
    if (!values.isObject()) {
      return false;
    }
    if ("MATCH_ANY".equals(op)) {
      return values.properties().stream()
          .anyMatch(entry -> entry.getValue().asText("").equals(labels.get(entry.getKey())));
    }
    return "MATCH_ALL".equals(op)
        && values.properties().stream()
            .allMatch(entry -> entry.getValue().asText("").equals(labels.get(entry.getKey())));
  }

  private boolean matchesPercentage(ClientContext context, JsonNode node) {
    String bucketKey = bucketValue(context, node.path("bucket_key").asText("clientId"));
    if (bucketKey.isBlank()) {
      return false;
    }
    int percentage = node.path("percentage").asInt(0);
    if (percentage <= 0) {
      return false;
    }
    if (percentage >= 100) {
      return true;
    }
    String salt = node.path("salt").asText("");
    int bucket = Math.floorMod(hash(bucketKey + ":" + salt), 10000);
    return bucket < percentage * 100;
  }

  private String bucketValue(ClientContext context, String bucketKey) {
    return switch (bucketKey) {
      case "appId" -> context.appId();
      case "clientIp" -> context.clientIp();
      case "instanceId", "clientId" -> context.clientId();
      default -> context.labels().getOrDefault(bucketKey, "");
    };
  }

  private boolean matchesComposite(ClientContext context, String op, JsonNode conditions) {
    if (!conditions.isArray()) {
      return false;
    }
    if ("OR".equals(op)) {
      for (JsonNode condition : conditions) {
        if (matchesNode(context, condition)) {
          return true;
        }
      }
      return false;
    }
    for (JsonNode condition : conditions) {
      if (!matchesNode(context, condition)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesCidr(String ip, String cidr) {
    String[] parts = cidr.split("/");
    if (parts.length != 2) {
      return false;
    }
    long ipValue = ipv4ToLong(ip);
    long networkValue = ipv4ToLong(parts[0]);
    int prefix = Integer.parseInt(parts[1]);
    if (prefix < 0 || prefix > 32) {
      return false;
    }
    long mask = prefix == 0 ? 0 : 0xffffffffL << (32 - prefix);
    return (ipValue & mask) == (networkValue & mask);
  }

  private long ipv4ToLong(String ip) {
    String[] parts = ip.split("\\.");
    if (parts.length != 4) {
      throw new IllegalArgumentException("Invalid IPv4 address");
    }
    long value = 0;
    for (String part : parts) {
      int octet = Integer.parseInt(part);
      if (octet < 0 || octet > 255) {
        throw new IllegalArgumentException("Invalid IPv4 address");
      }
      value = (value << 8) + octet;
    }
    return value;
  }

  private int hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return ((bytes[0] & 0xff) << 24)
          | ((bytes[1] & 0xff) << 16)
          | ((bytes[2] & 0xff) << 8)
          | (bytes[3] & 0xff);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
