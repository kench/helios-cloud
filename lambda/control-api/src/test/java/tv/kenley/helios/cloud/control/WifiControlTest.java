package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.DeviceUnreachable;
import tv.kenley.helios.cloud.control.model.SetWifiNetworkInput;
import tv.kenley.helios.cloud.control.model.WifiNetworkRefused;
import tv.kenley.helios.cloud.control.model.WifiSecurity;

class WifiControlTest {
  static final String ID = DeviceDirectoryTest.ID;
  static final String THING = DeviceDirectoryTest.THING;

  /** A clock tests move. */
  static final class Moving extends Clock {
    Instant now = Instant.parse("2026-11-22T21:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Executions a test scripts: each {@code get} answers the next state, then the last again. */
  static final class FakeCommands implements Commands {
    record Started(String thing, String command, String args, int timeout) {}

    final List<Started> started = new ArrayList<>();
    final Deque<Optional<Execution>> states = new ArrayDeque<>();
    Optional<Execution> last = Optional.empty();

    void answer(Execution... executions) {
      for (Execution execution : executions) {
        states.add(Optional.ofNullable(execution));
      }
    }

    @Override
    public String start(String thingName, String commandId, String args, int timeoutSeconds) {
      started.add(new Started(thingName, commandId, args, timeoutSeconds));
      return "exec-" + started.size();
    }

    @Override
    public Optional<Execution> get(String thingName, String executionId) {
      if (!states.isEmpty()) {
        last = states.poll();
      }
      return last;
    }

    /** The arguments the last command was started with. */
    Document args() {
      byte[] json = Base64.getDecoder().decode(started.getLast().args());
      return ShadowJson.parse(json);
    }
  }

  private ControlServiceTest.FakeShadows shadows;
  private FakeCommands commands;
  private Moving clock;
  private int pauses;
  private WifiControl wifi;
  private KeyPair device;

  @BeforeEach
  void setUp() throws GeneralSecurityException {
    shadows = new ControlServiceTest.FakeShadows();
    commands = new FakeCommands();
    clock = new Moving();
    DeviceDirectory devices = mock(DeviceDirectory.class);
    when(devices.get(ID))
        .thenReturn(
            Optional.of(
                DeviceSummary.builder()
                    .deviceId(ID)
                    .thingName(THING)
                    .role(DeviceRole.TRANSCODER)
                    .status(DeviceStatus.ACTIVE)
                    .build()));
    wifi =
        new WifiControl(
            devices,
            shadows,
            commands,
            clock,
            duration -> {
              pauses++;
              clock.now = clock.now.plus(duration);
            });
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    device = generator.generateKeyPair();
  }

  private byte[] devicePoint() {
    ECPublicKey key = (ECPublicKey) device.getPublic();
    byte[] out = new byte[65];
    out[0] = 0x04;
    byte[] x = key.getW().getAffineX().toByteArray();
    byte[] y = key.getW().getAffineY().toByteArray();
    System.arraycopy(
        x,
        Math.max(0, x.length - 32),
        out,
        1 + 32 - Math.min(32, x.length),
        Math.min(32, x.length));
    System.arraycopy(
        y,
        Math.max(0, y.length - 32),
        out,
        33 + 32 - Math.min(32, y.length),
        Math.min(32, y.length));
    return out;
  }

  private void reportSecretsKey(String suite) {
    String key = Base64.getUrlEncoder().withoutPadding().encodeToString(devicePoint());
    shadows.documents.put(
        THING + "/helios",
        ("{\"state\":{\"reported\":{\"secrets_key\":{\"suite\":\""
                + suite
                + "\",\"public\":\""
                + key
                + "\"}}}}")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static SetWifiNetworkInput input(String ssid, String passphrase) {
    SetWifiNetworkInput.Builder input = SetWifiNetworkInput.builder().deviceId(ID).ssid(ssid);
    if (passphrase != null) {
      input.passphrase(passphrase);
    }
    return input.build();
  }

  private static Commands.Execution succeeded(String result) {
    return new Commands.Execution("SUCCEEDED", null, null, result);
  }

  @Test
  void listsTheNetworksTheDeviceReports() {
    shadows.documents.put(
        THING + "/net",
        """
        {"state": {"reported": {"wifi": {"networks": [
          {"ssid": "Home", "security": "personal", "hidden": false},
          {"ssid": "Cafe", "security": "open", "hidden": true},
          {"ssid": "Odd", "security": "enterprise"},
          {"security": "open"}
        ]}}}}
        """
            .getBytes(StandardCharsets.UTF_8));
    var networks = wifi.list(ID).getNetworks();
    assertEquals(2, networks.size());
    assertEquals("Home", networks.get(0).getSsid());
    assertEquals(WifiSecurity.PERSONAL, networks.get(0).getSecurity());
    assertTrue(networks.get(1).isHidden());

    shadows.documents.put(THING + "/net", "{\"state\":{\"reported\":{}}}".getBytes());
    assertThrows(DeviceStateUnavailable.class, () -> wifi.list(ID));
    assertThrows(DeviceNotFound.class, () -> wifi.list("d-unknown"));
  }

  @Test
  void sealsThePassphraseToTheDeviceForThisSsid() throws Exception {
    reportSecretsKey(Sealing.SUITE);
    commands.answer(
        new Commands.Execution("CREATED", null, null, null),
        new Commands.Execution("IN_PROGRESS", null, null, null),
        succeeded("{\"network\":{\"ssid\":\"Home\",\"security\":\"personal\",\"hidden\":false}}"));

    var network = wifi.set(input("Home", "correct horse battery")).getNetwork();

    assertEquals("Home", network.getSsid());
    assertEquals(WifiSecurity.PERSONAL, network.getSecurity());
    assertEquals(2, pauses, "polled until it finished");
    FakeCommands.Started started = commands.started.getLast();
    assertEquals(THING, started.thing());
    assertEquals(WifiControl.SET_COMMAND, started.command());
    assertEquals(10, started.timeout());
    String json = new String(Base64.getDecoder().decode(started.args()), StandardCharsets.UTF_8);
    assertFalse(json.contains("correct horse"), "never sent in clear: " + json);

    Document args = commands.args();
    assertEquals("Home", ShadowJson.string(args, "ssid").orElseThrow());
    assertEquals(false, ShadowJson.bool(args, "hidden").orElseThrow());
    Sealing.Sealed sealed =
        new Sealing.Sealed(
            ShadowJson.string(args, "passphrase", "sealed", "enc").orElseThrow(),
            ShadowJson.string(args, "passphrase", "sealed", "ct").orElseThrow());
    byte[] opened =
        Sealing.open(
            device.getPrivate(),
            devicePoint(),
            sealed,
            Sealing.info(THING),
            "wifi.passphrase Home".getBytes(StandardCharsets.UTF_8));
    assertEquals("correct horse battery", new String(opened, StandardCharsets.UTF_8));
    assertThrows(
        GeneralSecurityException.class,
        () ->
            Sealing.open(
                device.getPrivate(),
                devicePoint(),
                sealed,
                Sealing.info(THING),
                "wifi.passphrase Cafe".getBytes(StandardCharsets.UTF_8)),
        "bound to its SSID");
    assertThrows(
        GeneralSecurityException.class,
        () ->
            Sealing.open(
                device.getPrivate(),
                devicePoint(),
                sealed,
                Sealing.info("t-other"),
                "wifi.passphrase Home".getBytes(StandardCharsets.UTF_8)),
        "bound to its device");
  }

  @Test
  void anOpenNetworkNeedsNoKey() {
    commands.answer(
        succeeded("{\"network\":{\"ssid\":\"Cafe\",\"security\":\"open\",\"hidden\":true}}"));
    var network =
        wifi.set(SetWifiNetworkInput.builder().deviceId(ID).ssid("Cafe").hidden(true).build())
            .getNetwork();
    assertEquals(WifiSecurity.OPEN, network.getSecurity());
    assertTrue(ShadowJson.at(commands.args(), "passphrase").isEmpty());
    assertEquals(true, ShadowJson.bool(commands.args(), "hidden").orElseThrow());
  }

  @Test
  void passphrasesNeedTheDevicesKey() {
    WifiNetworkRefused none =
        assertThrows(WifiNetworkRefused.class, () -> wifi.set(input("Home", "correct horse")));
    assertEquals("no_secrets_key", none.getReason());
    reportSecretsKey("hpke-x25519");
    WifiNetworkRefused other =
        assertThrows(WifiNetworkRefused.class, () -> wifi.set(input("Home", "correct horse")));
    assertEquals("no_secrets_key", other.getReason());
    shadows.documents.put(
        THING + "/helios",
        ("{\"state\":{\"reported\":{\"secrets_key\":{\"suite\":\"" + Sealing.SUITE + "\"}}}}")
            .getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "no_secrets_key",
        assertThrows(WifiNetworkRefused.class, () -> wifi.set(input("Home", "correct horse")))
            .getReason());
    assertTrue(commands.started.isEmpty(), "nothing sent");
  }

  @Test
  void passesTheDevicesRefusalsOn() {
    for (String[] row :
        new String[][] {
          {"FAILED", "NO_ADAPTER", "no_adapter"},
          {"FAILED", "TOO_MANY", "too_many"},
          {"FAILED", "SECRET_UNREADABLE", "secret_unreadable"},
          {"REJECTED", "UNKNOWN_COMMAND", "unsupported"},
          {"FAILED", "INTERNAL", "apply_failed"},
          {"FAILED", null, "apply_failed"},
        }) {
      commands.answer(new Commands.Execution(row[0], row[1], row[1] == null ? null : "why", null));
      WifiNetworkRefused refused =
          assertThrows(WifiNetworkRefused.class, () -> wifi.delete(ID, "Home"), row[1]);
      assertEquals(row[2], refused.getReason());
    }
  }

  @Test
  void silentDevicesAreUnreachable() {
    // AWS's timeout, the device's own, never finishing, and an interrupted wait.
    commands.answer(new Commands.Execution("TIMED_OUT", "$NO_RESPONSE_FROM_DEVICE", null, null));
    assertThrows(DeviceUnreachable.class, () -> wifi.delete(ID, "Home"));
    commands.answer(new Commands.Execution("FAILED", "TIMEOUT", "late", null));
    assertThrows(DeviceUnreachable.class, () -> wifi.delete(ID, "Home"));
    commands.answer(new Commands.Execution("CREATED", null, null, null));
    DeviceUnreachable never = assertThrows(DeviceUnreachable.class, () -> wifi.delete(ID, "Home"));
    assertTrue(never.getMessage().contains("10 s"), never.getMessage());
    assertTrue(pauses >= 25, "waited the timeout: " + pauses);
    commands.last = Optional.empty();
    WifiControl interrupted =
        new WifiControl(
            mock(
                DeviceDirectory.class,
                invocation ->
                    Optional.of(
                        DeviceSummary.builder()
                            .deviceId(ID)
                            .thingName(THING)
                            .role(DeviceRole.TRANSCODER)
                            .status(DeviceStatus.ACTIVE)
                            .build())),
            shadows,
            commands,
            clock,
            duration -> {
              throw new InterruptedException();
            });
    assertThrows(DeviceUnreachable.class, () -> interrupted.delete(ID, "Home"));
    assertTrue(Thread.interrupted(), "the interrupt is kept");
  }

  @Test
  void removesNetworks() {
    commands.answer(succeeded("{\"removed\":true}"));
    assertTrue(wifi.delete(ID, "Venue / Media").isRemoved());
    assertEquals(WifiControl.REMOVE_COMMAND, commands.started.getLast().command());
    assertEquals("Venue / Media", ShadowJson.string(commands.args(), "ssid").orElseThrow());
    commands.answer(succeeded(null));
    assertFalse(wifi.delete(ID, "Home").isRemoved(), "no result: not removed");
  }

  @Test
  void anAnswerWithoutTheNetworkIsAnError() {
    commands.answer(succeeded("{}"));
    assertThrows(
        IllegalStateException.class,
        () -> wifi.set(SetWifiNetworkInput.builder().deviceId(ID).ssid("Cafe").build()));
    assertEquals(Duration.ofMillis(400), CommandRunner.POLL);
  }

  @Test
  void keepsPassphrasesForOperators() {
    reportSecretsKey(Sealing.SUITE);
    StreamCommandsTest.MemorySettings settings = new StreamCommandsTest.MemorySettings();
    DeviceDirectory devices = mock(DeviceDirectory.class);
    when(devices.get(ID))
        .thenReturn(
            Optional.of(
                DeviceSummary.builder()
                    .deviceId(ID)
                    .thingName(THING)
                    .role(DeviceRole.TRANSCODER)
                    .status(DeviceStatus.ACTIVE)
                    .build()));
    WifiControl kept =
        new WifiControl(
            devices, shadows, new CommandRunner(commands, clock, duration -> {}), settings);

    commands.answer(
        succeeded("{\"network\":{\"ssid\":\"Home\",\"security\":\"personal\",\"hidden\":false}}"));
    assertEquals(
        "correct horse battery",
        kept.set(input("Home", "correct horse battery")).getNetwork().getPassphrase());
    commands.answer(
        succeeded("{\"network\":{\"ssid\":\"Cafe\",\"security\":\"open\",\"hidden\":false}}"));
    assertNull(kept.set(input("Cafe", null)).getNetwork().getPassphrase());

    shadows.documents.put(
        THING + "/net",
        """
        {"state": {"reported": {"wifi": {"networks": [
          {"ssid": "Home", "security": "personal", "hidden": false},
          {"ssid": "Cafe", "security": "open", "hidden": false},
          {"ssid": "Other", "security": "personal", "hidden": false}
        ]}}}}
        """
            .getBytes(StandardCharsets.UTF_8));
    var networks = kept.list(ID).getNetworks();
    assertEquals("correct horse battery", networks.get(0).getPassphrase());
    assertNull(networks.get(1).getPassphrase(), "open");
    assertNull(networks.get(2).getPassphrase(), "set some other way");

    commands.answer(succeeded("{\"removed\":true}"));
    kept.delete(ID, "Home");
    assertTrue(settings.items.isEmpty());

    // A refused change keeps nothing.
    commands.answer(new Commands.Execution("FAILED", "INVALID_PASSPHRASE", "too short", null));
    assertThrows(WifiNetworkRefused.class, () -> kept.set(input("Home", "correct horse battery")));
    assertTrue(settings.items.isEmpty());
  }
}
