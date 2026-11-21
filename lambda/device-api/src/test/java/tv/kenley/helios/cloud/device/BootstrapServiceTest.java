package tv.kenley.helios.cloud.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tv.kenley.helios.cloud.model.Adapter;
import tv.kenley.helios.cloud.model.AdapterKind;
import tv.kenley.helios.cloud.model.AmbiguousDevice;
import tv.kenley.helios.cloud.model.AnswerChallengeInput;
import tv.kenley.helios.cloud.model.AwsSettings;
import tv.kenley.helios.cloud.model.BootstrapInput;
import tv.kenley.helios.cloud.model.BootstrapResult;
import tv.kenley.helios.cloud.model.Capability;
import tv.kenley.helios.cloud.model.Challenge;
import tv.kenley.helios.cloud.model.ChallengeExpired;
import tv.kenley.helios.cloud.model.ChallengeFailed;
import tv.kenley.helios.cloud.model.ChallengeUnsupported;
import tv.kenley.helios.cloud.model.DeviceDisabled;
import tv.kenley.helios.cloud.model.DeviceIdentity;
import tv.kenley.helios.cloud.model.TokenLabels;
import tv.kenley.helios.cloud.model.UnknownDevice;

@ExtendWith(MockitoExtension.class)
final class BootstrapServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
  private static final String ETH = "00:d0:93:4a:ba:ae";
  private static final String WIFI = "00:d0:93:4a:ba:af";
  private static final AwsSettings DEFAULT_AWS =
      AwsSettings.builder()
          .region("us-west-2")
          .iotEndpoint("a2p9gwnksxjg9z-ats.iot.us-west-2.amazonaws.com")
          .credentialsEndpoint("c1u80y5c766riu.credentials.iot.us-west-2.amazonaws.com")
          .roleAlias("helios-device-role-alias")
          .build();

  @Mock private DeviceStore store;
  @Captor private ArgumentCaptor<LookupRecord> lookup;
  @Captor private ArgumentCaptor<ChallengeRecord> issued;

  private final List<String> warnings = new ArrayList<>();
  private KeyPair challengeKey;
  private BootstrapService service;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    challengeKey = generator.generateKeyPair();
    service = serviceAt(NOW);
  }

  private BootstrapService serviceAt(Instant now) {
    return new BootstrapService(
        store,
        Optional.of(DEFAULT_AWS),
        Clock.fixed(now, ZoneOffset.UTC),
        new SecureRandom(),
        warnings::add);
  }

  private static AuditEntry audit() {
    return new AuditEntry("Bootstrap", NOW, "203.0.113.7", "request-1");
  }

  private static Adapter adapter(String name, AdapterKind kind, String mac) {
    return Adapter.builder().interfaceName(name).kind(kind).mac(mac).build();
  }

  private static BootstrapInput request(boolean canAnswerChallenges, String... macs) {
    List<Adapter> adapters = new ArrayList<>();
    for (int i = 0; i < macs.length; i++) {
      adapters.add(adapter("eth" + i, AdapterKind.ETHERNET, macs[i]));
    }
    BootstrapInput.Builder input =
        BootstrapInput.builder()
            .adapters(adapters)
            .heliosVersion("0.1.0")
            .hardwareModel("PROXmobil3");
    if (canAnswerChallenges) {
      input.capabilities(List.of(Capability.TOKEN_CHALLENGE));
    }
    return input.build();
  }

  private DeviceRecord device(boolean disabled, boolean challengeRequired) {
    return new DeviceRecord(
        "d-1",
        "t-1",
        disabled,
        List.of(ETH),
        "123456",
        TokenLabels.builder().tokenLabel("helios").keyLabel("device-key").build(),
        challengeRequired,
        challengeRequired ? Optional.of(pem()) : Optional.empty(),
        Optional.empty());
  }

  private String pem() {
    return "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getEncoder().encodeToString(challengeKey.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
  }

  private void registered(DeviceRecord device, String... macs) {
    Map<String, String> index = new java.util.HashMap<>();
    for (String mac : macs) {
      index.put(mac, device.deviceId());
    }
    when(store.deviceIdsForMacs(anyCollection())).thenReturn(index);
    when(store.device(device.deviceId())).thenReturn(Optional.of(device));
  }

  private String lastResult() {
    verify(store).recordLookup(eq("d-1"), lookup.capture());
    return lookup.getValue().result();
  }

  @Test
  void returnsTheIdentityWithTheDefaultAwsSettings() {
    registered(device(false, false), ETH);
    AuditEntry audit = audit();

    BootstrapResult result = service.bootstrap(request(false, ETH), audit);

    DeviceIdentity identity =
        assertInstanceOf(BootstrapResult.IdentityMember.class, result).identity();
    assertEquals("d-1", identity.getDeviceId());
    assertEquals("t-1", identity.getThingName());
    assertEquals("123456", identity.getPin());
    assertEquals("device-key", identity.getToken().getKeyLabel());
    assertEquals(DEFAULT_AWS, identity.getAws());
    assertEquals("identity_returned", lastResult());
    assertEquals(Optional.of("0.1.0"), lookup.getValue().heliosVersion());
    assertEquals("203.0.113.7", lookup.getValue().sourceIp());
    assertTrue(audit.toJson().contains("\"deviceId\":\"d-1\""), audit.toJson());
  }

  @Test
  void prefersTheRecordsOwnAwsSettings() {
    AwsSettings own = DEFAULT_AWS.toBuilder().roleAlias("other-alias").build();
    DeviceRecord base = device(false, false);
    registered(
        new DeviceRecord(
            "d-1",
            "t-1",
            false,
            base.macs(),
            base.pin(),
            base.token(),
            false,
            Optional.empty(),
            Optional.of(own)),
        ETH);

    BootstrapResult result = service.bootstrap(request(false, ETH), audit());

    assertEquals(
        own, assertInstanceOf(BootstrapResult.IdentityMember.class, result).identity().getAws());
  }

  @Test
  void leavesAwsOutWithoutDefaults() {
    registered(device(false, false), ETH);
    BootstrapService noDefaults =
        new BootstrapService(
            store, Optional.empty(), Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), w -> {});

    BootstrapResult result = noDefaults.bootstrap(request(false, ETH), audit());

    assertNull(assertInstanceOf(BootstrapResult.IdentityMember.class, result).identity().getAws());
  }

  @Test
  void unknownMacsAreUnknownDevice() {
    when(store.deviceIdsForMacs(anyCollection())).thenReturn(Map.of());

    assertThrows(UnknownDevice.class, () -> service.bootstrap(request(false, ETH), audit()));
    verify(store, never()).recordLookup(anyString(), any());
  }

  @Test
  void macsOfTwoRecordsAreAmbiguous() {
    when(store.deviceIdsForMacs(anyCollection())).thenReturn(Map.of(ETH, "d-1", WIFI, "d-2"));

    assertThrows(
        AmbiguousDevice.class, () -> service.bootstrap(request(false, ETH, WIFI), audit()));
  }

  @Test
  void extraMacsDontBlockTheMatchButAreLogged() {
    registered(device(false, false), ETH);
    AuditEntry audit = audit();

    service.bootstrap(request(false, ETH, WIFI), audit);

    assertTrue(audit.toJson().contains("\"unregisteredMacs\":[\"" + WIFI + "\"]"), audit.toJson());
  }

  @Test
  void indexEntryForMissingRecordIsUnknownDevice() {
    when(store.deviceIdsForMacs(anyCollection())).thenReturn(Map.of(ETH, "d-gone"));
    when(store.device("d-gone")).thenReturn(Optional.empty());

    assertThrows(UnknownDevice.class, () -> service.bootstrap(request(false, ETH), audit()));
    assertEquals(1, warnings.size());
  }

  @Test
  void disabledDeviceIsRefusedAndRecorded() {
    registered(device(true, false), ETH);

    assertThrows(DeviceDisabled.class, () -> service.bootstrap(request(false, ETH), audit()));
    assertEquals("device_disabled", lastResult());
  }

  @Test
  void challengeNeedsTheCapability() {
    registered(device(false, true), ETH);

    assertThrows(ChallengeUnsupported.class, () -> service.bootstrap(request(false, ETH), audit()));
    assertEquals("challenge_unsupported", lastResult());
  }

  @Test
  void requiredChallengeWithoutKeyIsInternalError() {
    DeviceRecord base = device(false, true);
    registered(
        new DeviceRecord(
            "d-1",
            "t-1",
            false,
            base.macs(),
            base.pin(),
            base.token(),
            true,
            Optional.empty(),
            Optional.empty()),
        ETH);

    assertThrows(IllegalStateException.class, () -> service.bootstrap(request(true, ETH), audit()));
  }

  @Test
  void issuesChallengeBoundToTheDeviceAndMacs() {
    registered(device(false, true), ETH);
    AuditEntry audit = audit();

    BootstrapResult result = service.bootstrap(request(true, ETH, WIFI), audit);

    Challenge challenge =
        assertInstanceOf(BootstrapResult.ChallengeMember.class, result).challenge();
    verify(store).putChallenge(issued.capture());
    ChallengeRecord stored = issued.getValue();
    assertTrue(challenge.getChallengeId().matches("c-[0-9A-Z]{26}"), challenge.getChallengeId());
    assertEquals(challenge.getChallengeId(), stored.challengeId());
    assertEquals("d-1", stored.deviceId());
    assertEquals(List.of(ETH, WIFI), stored.macs());
    assertEquals(NOW.plusSeconds(60), challenge.getExpiresAt());
    assertEquals(32, challenge.getNonce().remaining());
    assertEquals(
        Base64.getEncoder().encodeToString(bytes(challenge.getNonce())), stored.nonceBase64());
    assertEquals("challenge_issued", lastResult());
    assertTrue(audit.toJson().contains("\"challenge\":true"), audit.toJson());
  }

  /** A challenge as issued for {@code macs}, and the device's signature over it. */
  private ChallengeRecord challengeFor(List<String> macs, Instant expiresAt) {
    return new ChallengeRecord("c-1", "d-1", macs, "AAECAwQ=", expiresAt);
  }

  private byte[] sign(ChallengeRecord challenge) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(challengeKey.getPrivate());
    signer.update(
        ChallengeSignature.message(
            challenge.challengeId(), challenge.nonceBase64(), challenge.macs()));
    return signer.sign();
  }

  private static AnswerChallengeInput answer(byte[] signature, String... macs) {
    List<Adapter> adapters = new ArrayList<>();
    for (String mac : macs) {
      adapters.add(adapter("eth0", AdapterKind.ETHERNET, mac));
    }
    return AnswerChallengeInput.builder()
        .challengeId("c-1")
        .signature(ByteBuffer.wrap(signature))
        .adapters(adapters)
        .build();
  }

  @Test
  void validAnswerGetsTheIdentity() throws Exception {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW.plusSeconds(30));
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));
    when(store.device("d-1")).thenReturn(Optional.of(device(false, true)));

    DeviceIdentity identity = service.answerChallenge(answer(sign(challenge), ETH), audit());

    assertEquals("123456", identity.getPin());
    assertEquals("identity_returned", lastResult());
  }

  @Test
  void usedOrUnknownChallengeHasExpired() {
    when(store.useChallenge("c-1")).thenReturn(Optional.empty());

    assertThrows(
        ChallengeExpired.class,
        () -> service.answerChallenge(answer(new byte[] {1}, ETH), audit()));
  }

  @Test
  void lateAnswerHasExpired() throws Exception {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW);
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));

    assertThrows(
        ChallengeExpired.class,
        () -> service.answerChallenge(answer(sign(challenge), ETH), audit()));
  }

  @Test
  void otherMacsFail() throws Exception {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW.plusSeconds(30));
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));
    when(store.device("d-1")).thenReturn(Optional.of(device(false, true)));

    assertThrows(
        ChallengeFailed.class,
        () -> service.answerChallenge(answer(sign(challenge), ETH, WIFI), audit()));
    assertEquals("challenge_failed", lastResult());
  }

  @Test
  void badSignatureFails() {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW.plusSeconds(30));
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));
    when(store.device("d-1")).thenReturn(Optional.of(device(false, true)));
    byte[] wrong = "not a signature".getBytes(StandardCharsets.UTF_8);

    assertThrows(ChallengeFailed.class, () -> service.answerChallenge(answer(wrong, ETH), audit()));
  }

  @Test
  void deletedDeviceFails() throws Exception {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW.plusSeconds(30));
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));
    when(store.device("d-1")).thenReturn(Optional.empty());

    assertThrows(
        ChallengeFailed.class,
        () -> service.answerChallenge(answer(sign(challenge), ETH), audit()));
  }

  @Test
  void keyRemovedSinceTheChallengeFails() throws Exception {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW.plusSeconds(30));
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));
    when(store.device("d-1")).thenReturn(Optional.of(device(false, false)));

    assertThrows(
        ChallengeFailed.class,
        () -> service.answerChallenge(answer(sign(challenge), ETH), audit()));
  }

  @Test
  void deviceDisabledSinceTheChallengeIsRefused() throws Exception {
    ChallengeRecord challenge = challengeFor(List.of(ETH), NOW.plusSeconds(30));
    when(store.useChallenge("c-1")).thenReturn(Optional.of(challenge));
    when(store.device("d-1")).thenReturn(Optional.of(device(true, true)));

    assertThrows(
        DeviceDisabled.class, () -> service.answerChallenge(answer(sign(challenge), ETH), audit()));
  }

  @Test
  void failingToRecordLastSeenDoesNotFailTheDevice() {
    registered(device(false, false), ETH);
    doThrow(new IllegalStateException("throttled")).when(store).recordLookup(anyString(), any());

    service.bootstrap(request(false, ETH), audit());

    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("throttled"));
  }

  private static byte[] bytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] out = new byte[copy.remaining()];
    copy.get(out);
    return out;
  }
}
