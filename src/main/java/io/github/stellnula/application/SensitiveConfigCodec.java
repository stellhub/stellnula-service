package io.github.stellnula.application;

import io.github.stellnula.config.DataPlaneProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SensitiveConfigCodec {

  private static final String PREFIX = "enc:v1:";
  private static final String MASKED_VALUE = "******";
  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 12;

  private final SecureRandom secureRandom = new SecureRandom();
  private final SecretKeySpec secretKey;

  public SensitiveConfigCodec(DataPlaneProperties properties) {
    this.secretKey = new SecretKeySpec(deriveKey(properties.sensitiveEncryptionKey()), "AES");
  }

  /** 加密敏感配置内容。 */
  public String encryptIfSensitive(boolean sensitive, String content) {
    if (!sensitive || content == null || content.startsWith(PREFIX)) {
      return content;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
      return PREFIX
          + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
          + ":"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to encrypt sensitive config", ex);
    }
  }

  /** 解密敏感配置内容。 */
  public String decryptIfSensitive(boolean sensitive, String content) {
    if (!sensitive || content == null || !content.startsWith(PREFIX)) {
      return content;
    }
    try {
      String[] parts = content.substring(PREFIX.length()).split(":", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException("Invalid encrypted sensitive config payload");
      }
      byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
      byte[] encrypted = Base64.getUrlDecoder().decode(parts[1]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to decrypt sensitive config", ex);
    }
  }

  /** 对敏感内容做脱敏展示。 */
  public String maskIfSensitive(boolean sensitive, String content) {
    return sensitive ? MASKED_VALUE : content;
  }

  private byte[] deriveKey(String value) {
    try {
      String key = value == null || value.isBlank() ? "stellnula-local-dev-sensitive-key" : value;
      return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Failed to derive sensitive config key", ex);
    }
  }
}
