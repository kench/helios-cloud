package tv.kenley.helios.cloud.control;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a secret to a device (LLD 3, section 10.7), so AWS IoT Commands, which keeps a command's
 * parameters, only ever holds ciphertext. HPKE (RFC 9180) in base mode with DHKEM(P-256,
 * HKDF-SHA256), HKDF-SHA256 and AES-128-GCM, the suite Helios opens ({@code identity/secrets.rs}),
 * with the JDK's own primitives. {@code info} binds the value to the device, the AAD to its
 * purpose.
 */
final class Sealing {
  /** The suite's name in the {@code helios} shadow's {@code secrets_key}. */
  static final String SUITE = "hpke-p256-sha256-aes128gcm";

  private static final int POINT_LENGTH = 65;
  private static final int COORDINATE_LENGTH = 32;
  private static final byte[] KEM_SUITE = concat(ascii("KEM"), new byte[] {0x00, 0x10});
  private static final byte[] HPKE_SUITE =
      concat(ascii("HPKE"), new byte[] {0x00, 0x10, 0x00, 0x01, 0x00, 0x01});
  private static final ECParameterSpec P256 = p256();

  private Sealing() {}

  /** A sealed value as Helios takes it: the encapsulated key and ciphertext, base64url. */
  record Sealed(String enc, String ct) {}

  /** {@code info} for a device: {@code helios secret v1 <thing>}. */
  static byte[] info(String thingName) {
    return ("helios secret v1 " + thingName).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Seals {@code plaintext} to the device whose public key is {@code recipientBase64} (base64url of
   * the uncompressed point, as its shadow reports it).
   *
   * @throws IllegalArgumentException if the key isn't a P-256 point
   */
  static Sealed seal(String recipientBase64, byte[] info, byte[] aad, byte[] plaintext) {
    byte[] recipient = Base64.getUrlDecoder().decode(recipientBase64);
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
      return seal(recipient, generator.generateKeyPair(), info, aad, plaintext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("P-256 isn't available", e);
    }
  }

  /** Seals with a given ephemeral key pair: for the RFC 9180 test vectors. */
  static Sealed seal(
      byte[] recipient, KeyPair ephemeral, byte[] info, byte[] aad, byte[] plaintext) {
    try {
      ECPublicKey recipientKey = publicKey(recipient);
      byte[] enc = encode(((ECPublicKey) ephemeral.getPublic()).getW());
      KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
      agreement.init(ephemeral.getPrivate());
      agreement.doPhase(recipientKey, true);
      byte[] dh = agreement.generateSecret();

      byte[] kemContext = concat(enc, recipient);
      byte[] eaePrk = labeledExtract(KEM_SUITE, new byte[0], "eae_prk", dh);
      byte[] sharedSecret = labeledExpand(KEM_SUITE, eaePrk, "shared_secret", kemContext, 32);

      byte[] pskIdHash = labeledExtract(HPKE_SUITE, new byte[0], "psk_id_hash", new byte[0]);
      byte[] infoHash = labeledExtract(HPKE_SUITE, new byte[0], "info_hash", info);
      byte[] context = concat(new byte[] {0x00}, concat(pskIdHash, infoHash));
      byte[] secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", new byte[0]);
      byte[] key = labeledExpand(HPKE_SUITE, secret, "key", context, 16);
      byte[] nonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", context, 12);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad);
      Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
      final Sealed sealed =
          new Sealed(base64.encodeToString(enc), base64.encodeToString(cipher.doFinal(plaintext)));
      Arrays.fill(dh, (byte) 0);
      Arrays.fill(sharedSecret, (byte) 0);
      Arrays.fill(secret, (byte) 0);
      Arrays.fill(key, (byte) 0);
      return sealed;
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("can't seal to that key: " + e.getMessage(), e);
    }
  }

  /**
   * Opens a sealed value as the device does: for tests, which check that what the Control API seals
   * opens only for the right device and purpose.
   *
   * @throws GeneralSecurityException if it doesn't authenticate
   */
  static byte[] open(
      java.security.PrivateKey recipient,
      byte[] recipientPoint,
      Sealed sealed,
      byte[] info,
      byte[] aad)
      throws GeneralSecurityException {
    byte[] enc = Base64.getUrlDecoder().decode(sealed.enc());
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(recipient);
    agreement.doPhase(publicKey(enc), true);
    byte[] dh = agreement.generateSecret();
    byte[] eaePrk = labeledExtract(KEM_SUITE, new byte[0], "eae_prk", dh);
    byte[] sharedSecret =
        labeledExpand(KEM_SUITE, eaePrk, "shared_secret", concat(enc, recipientPoint), 32);
    byte[] pskIdHash = labeledExtract(HPKE_SUITE, new byte[0], "psk_id_hash", new byte[0]);
    byte[] infoHash = labeledExtract(HPKE_SUITE, new byte[0], "info_hash", info);
    byte[] context = concat(new byte[] {0x00}, concat(pskIdHash, infoHash));
    byte[] secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", new byte[0]);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(labeledExpand(HPKE_SUITE, secret, "key", context, 16), "AES"),
        new GCMParameterSpec(128, labeledExpand(HPKE_SUITE, secret, "base_nonce", context, 12)));
    cipher.updateAAD(aad);
    return cipher.doFinal(Base64.getUrlDecoder().decode(sealed.ct()));
  }

  /** The P-256 public key at an uncompressed point. */
  static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
    if (point.length != POINT_LENGTH || point[0] != 0x04) {
      throw new IllegalArgumentException("not an uncompressed P-256 point");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + COORDINATE_LENGTH));
    BigInteger y =
        new BigInteger(1, Arrays.copyOfRange(point, 1 + COORDINATE_LENGTH, POINT_LENGTH));
    return (ECPublicKey)
        KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
  }

  /** P-256's domain parameters. */
  static ECParameterSpec p256() {
    try {
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("P-256 isn't available", e);
    }
  }

  private static byte[] encode(ECPoint point) {
    byte[] out = new byte[POINT_LENGTH];
    out[0] = 0x04;
    copyCoordinate(point.getAffineX(), out, 1);
    copyCoordinate(point.getAffineY(), out, 1 + COORDINATE_LENGTH);
    return out;
  }

  private static void copyCoordinate(BigInteger value, byte[] out, int offset) {
    byte[] bytes = value.toByteArray();
    int start = Math.max(0, bytes.length - COORDINATE_LENGTH);
    int length = bytes.length - start;
    System.arraycopy(bytes, start, out, offset + COORDINATE_LENGTH - length, length);
  }

  /** {@code LabeledExtract}: HKDF-Extract over {@code "HPKE-v1" || suite || label || ikm}. */
  private static byte[] labeledExtract(byte[] suite, byte[] salt, String label, byte[] ikm)
      throws GeneralSecurityException {
    return hmac(
        salt.length == 0 ? new byte[32] : salt,
        concat(ascii("HPKE-v1"), concat(suite, concat(ascii(label), ikm))));
  }

  /** {@code LabeledExpand}: HKDF-Expand with {@code I2OSP(L, 2) || "HPKE-v1" || suite || label}. */
  private static byte[] labeledExpand(
      byte[] suite, byte[] prk, String label, byte[] info, int length)
      throws GeneralSecurityException {
    byte[] labeled =
        concat(
            new byte[] {(byte) (length >> 8), (byte) length},
            concat(ascii("HPKE-v1"), concat(suite, concat(ascii(label), info))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] block = new byte[0];
    for (int counter = 1; out.size() < length; counter++) {
      block = hmac(prk, concat(block, concat(labeled, new byte[] {(byte) counter})));
      out.writeBytes(block);
    }
    return Arrays.copyOf(out.toByteArray(), length);
  }

  private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
