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
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.core.schema.Unit;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.BitrateSetting;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.DeviceUnreachable;
import tv.kenley.helios.cloud.control.model.RtmpSource;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationInput;
import tv.kenley.helios.cloud.control.model.Source;
import tv.kenley.helios.cloud.control.model.StreamChangeRefused;
import tv.kenley.helios.cloud.control.model.StreamDescription;
import tv.kenley.helios.cloud.control.model.StreamNotFound;
import tv.kenley.helios.cloud.control.model.UpdateStreamInput;

class StreamCommandsTest {
  static final String ID = DeviceDirectoryTest.ID;
  static final String THING = DeviceDirectoryTest.THING;

  /** The settings store in memory. */
  static final class MemorySettings implements SettingsStore {
    final Map<String, Map<String, Object>> items = new HashMap<>();

    @Override
    public Optional<Document> get(String deviceId, String item) {
      return Optional.ofNullable(items.get(deviceId + "/" + item))
          .map(value -> ShadowJson.parse(ShadowJson.bytes(value)));
    }

    @Override
    public Map<String, Document> all(String deviceId, String prefix) {
      Map<String, Document> found = new LinkedHashMap<>();
      items.forEach((key, value) -> {
        String start = deviceId + "/" + prefix;
        if (key.startsWith(start)) {
          found.put(key.substring(start.length()), ShadowJson.parse(ShadowJson.bytes(value)));
        }
      });
      return found;
    }

    @Override
    public void put(String deviceId, String item, Map<String, Object> value) {
      items.put(deviceId + "/" + item, new LinkedHashMap<>(value));
    }

    @Override
    public void delete(String deviceId, String item) {
      items.remove(deviceId + "/" + item);
    }
  }

  private static final String STATUS =
      """
      {"status": {"state": "stopped", "since": "2026-11-26T22:00:00Z", "desired": "stopped",
        "source": {"type": "rtmp", "camera": 1}, "profile": "cam", "bitrate_kbps": 2500,
        "bitrate_override_kbps": 2500,
        "destination": "srt://ingest.example.com:4001?streamid=publish/live/pocket"}}
      """;

  private ControlServiceTest.FakeShadows shadows;
  private WifiControlTest.FakeCommands commands;
  private MemorySettings settings;
  private DeviceDirectory devices;
  private ControlService service;
  private KeyPair key;

  @BeforeEach
  void setUp() throws GeneralSecurityException {
    shadows = new ControlServiceTest.FakeShadows();
    commands = new WifiControlTest.FakeCommands();
    settings = new MemorySettings();
    devices = mock(DeviceDirectory.class);
    device("0.16.0");
    WifiControlTest.Moving clock = new WifiControlTest.Moving();
    CommandRunner runner =
        new CommandRunner(commands, clock, duration -> clock.now = clock.now.plus(duration));
    service = new ControlService(devices, shadows, new StreamCommands(runner, shadows, settings));
    shadows.documents.put(
        THING + "/stream", StreamShadowTest.fixture("stream-shadow-control.json"));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    key = generator.generateKeyPair();
  }

  private void device(String version) {
    DeviceSummary.Builder device =
        DeviceSummary.builder()
            .deviceId(ID)
            .thingName(THING)
            .role(DeviceRole.TRANSCODER)
            .status(DeviceStatus.ACTIVE);
    if (version != null) {
      device.heliosVersion(version);
    }
    when(devices.get(ID)).thenReturn(Optional.of(device.build()));
  }

  private static Commands.Execution succeeded(String result) {
    return new Commands.Execution("SUCCEEDED", null, null, result);
  }

  private static Commands.Execution failed(String code, String description) {
    return new Commands.Execution("FAILED", code, description, null);
  }

  @Test
  void devicesWithHelios016OrLaterTakeCommands() {
    for (String version : List.of("0.16.0", "0.16.1", "0.17.0", "1.0.0", "0.16.0-1")) {
      assertTrue(StreamCommands.supports(summary(version)), version);
    }
    for (String version : List.of("0.15.0", "0.9.9", "0.15.99", "x.y")) {
      assertFalse(StreamCommands.supports(summary(version)), version);
    }
    assertFalse(StreamCommands.supports(summary(null)));
  }

  private static DeviceSummary summary(String version) {
    DeviceSummary.Builder device =
        DeviceSummary.builder()
            .deviceId(ID)
            .thingName(THING)
            .role(DeviceRole.TRANSCODER)
            .status(DeviceStatus.ACTIVE);
    if (version != null) {
      device.heliosVersion(version);
    }
    return device.build();
  }

  @Test
  void updatesWithOneCommandAndShowsWhatWasSet() {
    commands.answer(succeeded(STATUS));
    StreamDescription stream =
        service
            .update(
                UpdateStreamInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .source(
                        Source.builder().rtmp(RtmpSource.builder().key("ingest-1").build()).build())
                    .profile("cam")
                    .bitrate(BitrateSetting.builder().overrideKbps(2500).build())
                    .sourceCeilingPercent(80)
                    .record(true)
                    .build())
            .getStream();

    assertEquals(StreamCommands.UPDATE, commands.started.getLast().command());
    assertEquals(
        ShadowJson.parse(
            """
            {"stream_id": "pocket", "source": {"set": {"rtmp": {"key": "ingest-1"}}},
             "profile": {"set": "cam"}, "bitrate": {"set": 2500},
             "source_ceiling_percent": {"set": 80}, "record": {"set": true}}
            """
                .getBytes(StandardCharsets.UTF_8)),
        commands.args());
    assertFalse(stream.isPending());
    assertEquals("cam", stream.getDesired().getProfile());
    assertEquals(
        2500, ((BitrateSetting.OverrideKbpsMember) stream.getDesired().getBitrate()).getValue());
    assertEquals(80, stream.getDesired().getSourceCeilingPercent());
    assertEquals("cam", stream.getReported().getProfile(), "reported from the command's answer");

    // Kept for GetStream, and the stream's old desired state cleared.
    assertEquals("cam", service.getStream(ID, "pocket").getStream().getDesired().getProfile());
    assertTrue(
        shadows
            .updates
            .getLast()
            .endsWith("{\"state\":{\"desired\":{\"streams\":{\"pocket\":null}}}}"),
        shadows.updates.getLast());

    // Back to the profile's bitrate: "configured", kept as no override.
    commands.answer(succeeded(STATUS));
    stream =
        service
            .update(
                UpdateStreamInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .bitrate(BitrateSetting.builder().profileDefault(Unit.getInstance()).build())
                    .build())
            .getStream();
    assertEquals(
        ShadowJson.parse(
            "{\"stream_id\": \"pocket\", \"bitrate\": {\"configured\": {}}}"
                .getBytes(StandardCharsets.UTF_8)),
        commands.args());
    assertTrue(stream.getDesired().getBitrate() instanceof BitrateSetting.ProfileDefaultMember);
    assertEquals("cam", stream.getDesired().getProfile(), "the earlier settings stay");
  }

  @Test
  void refusalChangesNothingAndListsEveryReason() {
    commands.answer(
        succeeded(
            """
            {"status": {"state": "stopped"}, "refused": [
              {"setting": "profile", "reason": "no profile \\"x\\""},
              {"setting": "source_ceiling_percent", "reason": "too high"}]}
            """));
    StreamChangeRefused refused =
        assertThrows(
            StreamChangeRefused.class,
            () ->
                service.update(
                    UpdateStreamInput.builder()
                        .deviceId(ID)
                        .streamId("pocket")
                        .profile("cam")
                        .sourceCeilingPercent(300)
                        .build()));
    assertEquals(
        List.of("profile", "sourceCeilingPercent"),
        refused.getRefused().stream().map(r -> r.getSetting()).toList());
    assertTrue(settings.items.isEmpty(), "nothing recorded");
    assertTrue(shadows.updates.isEmpty(), "desired state untouched");
  }

  @Test
  void startsAndStopsWithCommands() {
    commands.answer(succeeded(STATUS));
    StreamDescription stream = service.start(ID, "backpack", "token-1").getStream();
    assertEquals(StreamCommands.START, commands.started.getLast().command());
    assertEquals(
        ShadowJson.parse("{\"stream_id\": \"backpack\"}".getBytes(StandardCharsets.UTF_8)),
        commands.args());
    assertEquals("live", stream.getDesired().getState().getValue());
    assertFalse(stream.isPending());

    // A Helios before 0.16.0 answered with the status itself.
    commands.answer(
        succeeded(
            """
            {"state": "stopped", "since": "2026-11-26T22:00:00Z", "desired": "stopped",
             "source": {"type": "test"}, "profile": "hevc", "bitrate_kbps": 1000,
             "destination": "srt://ingest.example.com:4001?streamid=x"}
            """));
    stream = service.stop(ID, "backpack", "token-2").getStream();
    assertEquals(StreamCommands.STOP, commands.started.getLast().command());
    assertEquals("stopped", stream.getDesired().getState().getValue());
    assertEquals("hevc", stream.getReported().getProfile());
  }

  @Test
  void sealsTheDestinationForTheStream() throws GeneralSecurityException {
    reportKey();
    String url =
        "srtla://ingest.example.com:5000?streamid=publish/live/pocket&passphrase=a-passphrase-1";
    commands.answer(succeeded(STATUS));
    var output =
        service.setDestination(
            SetStreamDestinationInput.builder().deviceId(ID).streamId("pocket").url(url).build());

    Document args = commands.args();
    Document sealed = ShadowJson.at(args, "destination", "set", "sealed").orElseThrow();
    assertFalse(
        new String(
                Base64.getDecoder().decode(commands.started.getLast().args()),
                StandardCharsets.UTF_8)
            .contains("a-passphrase-1"),
        "never in the command's parameters");
    byte[] opened =
        Sealing.open(
            key.getPrivate(),
            point(),
            new Sealing.Sealed(
                ShadowJson.string(sealed, "enc").orElseThrow(),
                ShadowJson.string(sealed, "ct").orElseThrow()),
            Sealing.info(THING),
            "stream.destination pocket".getBytes(StandardCharsets.UTF_8));
    assertEquals(url, new String(opened, StandardCharsets.UTF_8));
    assertEquals(url, output.getStream().getDesired().getDestination(), "operators see it");
    assertEquals(
        "srt://ingest.example.com:4001?streamid=publish/live/pocket&latency=2000",
        output.getPrevious(),
        "nothing set through this API yet: the reported one");

    // The next change shows this one as the previous.
    commands.answer(succeeded(STATUS));
    String next = "srt://other.example.com:4000?streamid=x";
    assertEquals(
        url,
        service
            .setDestination(
                SetStreamDestinationInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .url(next)
                    .build())
            .getPrevious());
  }

  @Test
  void destinationNeedsKeyToSealTo() {
    assertThrows(
        StreamChangeRefused.class,
        () ->
            service.setDestination(
                SetStreamDestinationInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .url("srt://ingest.example.com:4001?streamid=x")
                    .build()));
    assertTrue(commands.started.isEmpty());
  }

  @Test
  void failuresMapToTheApisErrors() {
    commands.answer(failed("INVALID_MESSAGE", "no stream \"pocket\""));
    assertThrows(StreamNotFound.class, () -> service.start(ID, "pocket", null));
    commands.answer(failed("MODULE_UNAVAILABLE", "the stream daemon is unreachable"));
    assertThrows(DeviceStateUnavailable.class, () -> service.start(ID, "pocket", null));
    commands.answer(failed("TIMEOUT", "late"));
    assertThrows(DeviceUnreachable.class, () -> service.start(ID, "pocket", null));
    commands.answer(failed("INTERNAL", "oops"));
    assertThrows(IllegalStateException.class, () -> service.start(ID, "pocket", null));
    // No answer at all.
    commands.answer(new Commands.Execution("IN_PROGRESS", null, null, null));
    assertThrows(DeviceUnreachable.class, () -> service.start(ID, "pocket", null));
    assertTrue(settings.items.isEmpty());
  }

  @Test
  void olderDevicesStayOnDesiredState() {
    device("0.15.0");
    StreamDescription stream = service.start(ID, "backpack", "token").getStream();
    assertTrue(commands.started.isEmpty());
    assertTrue(shadows.updates.getLast().contains("\"backpack\":{\"state\":\"live\"}"));
    assertEquals("live", stream.getDesired().getState().getValue());
  }

  @Test
  void listsStreamsWithWhatWasSet() {
    settings.put(ID, SettingsStore.stream("pattern"), Map.of("state", "live", "profile", "odd"));
    var streams = service.listStreams(ID).getStreams();
    StreamDescription pattern =
        streams.stream().filter(s -> s.getStreamId().equals("pattern")).findFirst().orElseThrow();
    assertEquals("odd", pattern.getDesired().getProfile());
    assertFalse(pattern.isPending());
    StreamDescription pocket =
        streams.stream().filter(s -> s.getStreamId().equals("pocket")).findFirst().orElseThrow();
    assertNull(pocket.getDesired().getProfile(), "the shadow's desired state isn't shown");
    assertFalse(pocket.isPending());
  }

  private void reportKey() {
    String public64 = Base64.getUrlEncoder().withoutPadding().encodeToString(point());
    shadows.documents.put(
        THING + "/helios",
        ("{\"state\":{\"reported\":{\"secrets_key\":{\"suite\":\""
                + Sealing.SUITE
                + "\",\"public\":\""
                + public64
                + "\"}}}}")
            .getBytes(StandardCharsets.UTF_8));
  }

  private byte[] point() {
    ECPublicKey ec = (ECPublicKey) key.getPublic();
    byte[] out = new byte[65];
    out[0] = 0x04;
    byte[] x = ec.getW().getAffineX().toByteArray();
    byte[] y = ec.getW().getAffineY().toByteArray();
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
}
