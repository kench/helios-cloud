package tv.kenley.helios.cloud.common;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** Challenge public keys: P-256 SubjectPublicKeyInfo in PEM (Bootstrap API, sections 3.2 and 4). */
public final class ChallengeKeys {
  private static final BigInteger P256_ORDER = p256Order();

  private ChallengeKeys() {}

  /**
   * The P-256 public key in {@code pem}.
   *
   * @throws IllegalArgumentException if it isn't one
   */
  public static ECPublicKey parseP256(String pem) {
    String base64 =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
      if (key instanceof ECPublicKey ec && ec.getParams().getOrder().equals(P256_ORDER)) {
        return ec;
      }
      throw new IllegalArgumentException("not a P-256 public key");
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not an EC public key in PEM", e);
    }
  }

  /** SHA-256 of the key's DER SubjectPublicKeyInfo, as lowercase hex (the keyFingerprint). */
  public static String fingerprint(PublicKey key) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the JDK has no SHA-256", e);
    }
  }

  private static BigInteger p256Order() {
    try {
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class).getOrder();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the JDK has no P-256", e);
    }
  }
}
