package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SealingTest {
  // RFC 9180, appendix A.3.1: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, base mode.
  static final String INFO = "4f6465206f6e2061204772656369616e2055726e";
  static final String PK_EM =
      "04"
          + "a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac9"
          + "8536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4";
  static final String SK_EM = "4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb";
  static final String PK_RM =
      "04"
          + "fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a82"
          + "6a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0";
  static final String PT = "4265617574792069732074727574682c20747275746820626561757479";
  static final String AAD_0 = "436f756e742d30";
  static final String CT_0 =
      "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434";

  static byte[] hex(String text) {
    return HexFormat.of().parseHex(text);
  }

  static KeyPair ephemeral() throws Exception {
    PrivateKey secret =
        KeyFactory.getInstance("EC")
            .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, hex(SK_EM)), Sealing.p256()));
    return new KeyPair(Sealing.publicKey(hex(PK_EM)), secret);
  }

  @Test
  void sealsAsRfc9180Does() throws Exception {
    Sealing.Sealed sealed = Sealing.seal(hex(PK_RM), ephemeral(), hex(INFO), hex(AAD_0), hex(PT));
    assertArrayEquals(hex(PK_EM), Base64.getUrlDecoder().decode(sealed.enc()));
    assertArrayEquals(hex(CT_0), Base64.getUrlDecoder().decode(sealed.ct()));
    assertEquals("Beauty is truth, truth beauty", new String(hex(PT), StandardCharsets.UTF_8));
  }

  @Test
  void everySealUsesNewKeys() {
    String recipient = Base64.getUrlEncoder().withoutPadding().encodeToString(hex(PK_RM));
    byte[] info = Sealing.info("t-1");
    byte[] aad = "wifi.passphrase Home".getBytes(StandardCharsets.UTF_8);
    Sealing.Sealed first = Sealing.seal(recipient, info, aad, "correct horse".getBytes());
    Sealing.Sealed second = Sealing.seal(recipient, info, aad, "correct horse".getBytes());
    assertNotEquals(first.enc(), second.enc());
    assertNotEquals(first.ct(), second.ct());
    assertEquals(65, Base64.getUrlDecoder().decode(first.enc()).length);
    // The plaintext and a 16-byte tag.
    assertEquals(13 + 16, Base64.getUrlDecoder().decode(first.ct()).length);
    assertArrayEquals("helios secret v1 t-1".getBytes(StandardCharsets.UTF_8), Sealing.info("t-1"));
  }

  @Test
  void refusesKeysThatArentP256Points() {
    for (byte[] bad : new byte[][] {new byte[64], new byte[65], hex("02" + PK_RM.substring(2))}) {
      String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bad);
      assertThrows(
          IllegalArgumentException.class,
          () -> Sealing.seal(key, new byte[0], new byte[0], new byte[] {1}));
    }
  }
}
