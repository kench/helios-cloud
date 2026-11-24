package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.core.schema.Unit;
import tv.kenley.helios.cloud.control.model.BitrateSetting;
import tv.kenley.helios.cloud.control.model.DesiredState;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.InvalidDestination;
import tv.kenley.helios.cloud.control.model.RtmpSource;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationInput;
import tv.kenley.helios.cloud.control.model.SettingUnavailable;
import tv.kenley.helios.cloud.control.model.Source;
import tv.kenley.helios.cloud.control.model.StreamNotFound;
import tv.kenley.helios.cloud.control.model.TestSource;
import tv.kenley.helios.cloud.control.model.UpdateStreamInput;

class ControlServiceTest {
  static final String ID = DeviceDirectoryTest.ID;
  static final String THING = DeviceDirectoryTest.THING;

  /** Shadows in memory, recording every update. */
  static final class FakeShadows implements Shadows {
    final Map<String, byte[]> documents = new HashMap<>();
    final List<String> updates = new ArrayList<>();
    long version = 43;

    @Override
    public Optional<byte[]> get(String thingName, String name) {
      return Optional.ofNullable(documents.get(thingName + "/" + name));
    }

    @Override
    public long update(String thingName, String name, byte[] document) {
      updates.add(thingName + "/" + name + " " + new String(document, StandardCharsets.UTF_8));
      return version++;
    }
  }

  private FakeShadows shadows;
  private DeviceDirectory devices;
  private ControlService service;

  @BeforeEach
  void setUp() {
    shadows = new FakeShadows();
    devices = mock(DeviceDirectory.class);
    DeviceSummary device =
        DeviceSummary.builder()
            .deviceId(ID)
            .thingName(THING)
            .role(DeviceRole.TRANSCODER)
            .status(DeviceStatus.ACTIVE)
            .build();
    when(devices.get(ID)).thenReturn(Optional.of(device));
    when(devices.get("d-01M80KB9C9T0ZSPC64NZVY0000")).thenReturn(Optional.empty());
    service = new ControlService(devices, shadows);
  }

  private void streamShadow(String fixture) {
    shadows.documents.put(THING + "/stream", StreamShadowTest.fixture(fixture));
  }

  @Test
  void listsDevicesInPages() {
    when(devices.list(null, ControlService.DEFAULT_PAGE))
        .thenReturn(new DeviceDirectory.Page(List.of(), "next"));
    when(devices.list("next", 5)).thenReturn(new DeviceDirectory.Page(List.of(), null));
    assertEquals("next", service.listDevices(null, null).getNextToken());
    assertNull(service.listDevices("next", 5).getNextToken());
  }

  @Test
  void getsDeviceWithItsModules() {
    assertTrue(service.getDevice(ID).getModules().isEmpty(), "no helios shadow");
    shadows.documents.put(
        THING + "/helios",
        "{\"state\":{\"reported\":{\"modules\":{\"usb\":{\"state\":\"running\"},\"x\":{}}}}}"
            .getBytes(StandardCharsets.UTF_8));
    assertEquals(Map.of("usb", "running"), service.getDevice(ID).getModules());
    assertThrows(DeviceNotFound.class, () -> service.getDevice("d-01M80KB9C9T0ZSPC64NZVY0000"));
  }

  @Test
  void inputsAndProfilesNeedDeviceThatReportsThem() {
    assertThrows(DeviceStateUnavailable.class, () -> service.listInputs(ID), "no shadow");
    streamShadow("stream-shadow-current.json");
    assertThrows(DeviceStateUnavailable.class, () -> service.listInputs(ID));
    assertThrows(DeviceStateUnavailable.class, () -> service.listProfiles(ID));
    streamShadow("stream-shadow-control.json");
    assertEquals(5, service.listInputs(ID).getInputs().size());
    assertEquals(2, service.listProfiles(ID).getProfiles().size());
  }

  @Test
  void listsAndGetsStreams() {
    streamShadow("stream-shadow-control.json");
    assertEquals(3, service.listStreams(ID).getStreams().size());
    assertEquals("pocket", service.getStream(ID, "pocket").getStream().getStreamId());
    assertThrows(StreamNotFound.class, () -> service.getStream(ID, "nope"));
  }

  @Test
  void startsAndStopsThroughTheDesiredState() {
    streamShadow("stream-shadow-current.json");
    var started = service.start(ID, "pocket", "tok-1");
    assertEquals(43, started.getShadowVersion());
    assertEquals(DesiredState.LIVE, started.getStream().getDesired().getState());
    var stopped = service.stop(ID, "pocket", null);
    assertEquals(DesiredState.STOPPED, stopped.getStream().getDesired().getState());
    assertTrue(stopped.getStream().isPending());
    assertEquals(
        List.of(
            THING
                + "/stream {\"state\":{\"desired\":{\"streams\":{\"pocket\":"
                + "{\"state\":\"live\"}}}},\"clientToken\":\"tok-1\"}",
            THING
                + "/stream {\"state\":{\"desired\":{\"streams\":{\"pocket\":"
                + "{\"state\":\"stopped\"}}}}}"),
        shadows.updates);
    assertThrows(StreamNotFound.class, () -> service.start(ID, "nope", null));
  }

  @Test
  void updatesOnlyWhatsGiven() {
    streamShadow("stream-shadow-control.json");
    UpdateStreamInput input =
        UpdateStreamInput.builder()
            .deviceId(ID)
            .streamId("pocket")
            .source(Source.builder().rtmp(RtmpSource.builder().key("ingest").build()).build())
            .profile("cam")
            .bitrate(BitrateSetting.builder().overrideKbps(2500).build())
            .sourceCeilingPercent(60)
            .build();
    var updated = service.update(input);
    assertEquals("cam", updated.getStream().getDesired().getProfile());
    assertEquals(
        THING
            + "/stream {\"state\":{\"desired\":{\"streams\":{\"pocket\":{"
            + "\"source\":{\"type\":\"rtmp\",\"video\":null,\"audio\":null,\"camera\":null,"
            + "\"size\":null,\"fps\":null},\"profile\":\"cam\",\"bitrate_kbps\":2500,"
            + "\"source_ceiling_percent\":60}}}}}",
        shadows.updates.getLast());

    service.update(
        UpdateStreamInput.builder()
            .deviceId(ID)
            .streamId("backpack")
            .bitrate(BitrateSetting.builder().profileDefault(Unit.getInstance()).build())
            .clientToken("tok-2")
            .build());
    assertEquals(
        THING
            + "/stream {\"state\":{\"desired\":{\"streams\":{\"backpack\":"
            + "{\"bitrate_kbps\":null}}}},\"clientToken\":\"tok-2\"}",
        shadows.updates.getLast());
  }

  @Test
  void clearsThePreviousSourcesFields() {
    // pocket's desired source is rtmp camera 1: a switch to a test pattern must clear `camera`, or
    // the shadow's merge leaves it there and the device refuses the merged source.
    streamShadow("stream-shadow-control.json");
    var updated =
        service.update(
            UpdateStreamInput.builder()
                .deviceId(ID)
                .streamId("pocket")
                .source(
                    Source.builder()
                        .test(TestSource.builder().size("1280x720").fps(30).build())
                        .build())
                .build());
    assertEquals(
        THING
            + "/stream {\"state\":{\"desired\":{\"streams\":{\"pocket\":{\"source\":"
            + "{\"type\":\"test\",\"size\":\"1280x720\",\"fps\":30,\"video\":null,"
            + "\"audio\":null,\"camera\":null}}}}}}",
        shadows.updates.getLast());
    assertEquals(
        Source.builder().test(TestSource.builder().size("1280x720").fps(30).build()).build(),
        updated.getStream().getDesired().getSource(),
        "the answer shows the source as the shadow will hold it, without the nulls");
  }

  @Test
  void refusesSourcesAndProfilesTheDeviceDoesntHave() {
    streamShadow("stream-shadow-control.json");
    SettingUnavailable source =
        assertThrows(
            SettingUnavailable.class,
            () ->
                service.update(
                    UpdateStreamInput.builder()
                        .deviceId(ID)
                        .streamId("pocket")
                        .source(
                            Source.builder()
                                .rtmp(RtmpSource.builder().key("ingest-7").build())
                                .build())
                        .build()));
    assertEquals("source", source.getSetting());
    SettingUnavailable profile =
        assertThrows(
            SettingUnavailable.class,
            () ->
                service.update(
                    UpdateStreamInput.builder()
                        .deviceId(ID)
                        .streamId("pocket")
                        .profile("nope")
                        .build()));
    assertEquals("profile", profile.getSetting());
    assertTrue(shadows.updates.isEmpty(), "nothing written");
  }

  @Test
  void setsDestinationInItsNormalForm() {
    streamShadow("stream-shadow-control.json");
    var output =
        service.setDestination(
            SetStreamDestinationInput.builder()
                .deviceId(ID)
                .streamId("pocket")
                .url("srt://other.example.com:4001?latency=1500&streamid=publish/live/p")
                .build());
    assertEquals(StreamShadowTest.POCKET_URL, output.getPrevious());
    assertEquals(
        "srt://other.example.com:4001?streamid=publish/live/p&latency=1500",
        output.getStream().getDesired().getDestination());
    assertTrue(output.getStream().isPending());
    assertEquals(
        THING
            + "/stream {\"state\":{\"desired\":{\"streams\":{\"pocket\":{\"destination\":"
            + "\"srt://other.example.com:4001?streamid=publish/live/p&latency=1500\"}}}}}",
        shadows.updates.getLast());

    streamShadow("stream-shadow-current.json");
    assertNull(
        service
            .setDestination(
                SetStreamDestinationInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .url("srtla://10.0.0.1:5000")
                    .build())
            .getPrevious(),
        "nothing desired or reported before");

    int writes = shadows.updates.size();
    assertThrows(
        InvalidDestination.class,
        () ->
            service.setDestination(
                SetStreamDestinationInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .url("srt://host:1?mode=listener")
                    .build()));
    assertEquals(writes, shadows.updates.size(), "an invalid destination writes nothing");
  }
}
