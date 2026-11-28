package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceRole;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceStatus;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.InvalidDestination;
import tv.kenley.helios.cloud.control.model.RecordingState;
import tv.kenley.helios.cloud.control.model.RtmpSource;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationInput;
import tv.kenley.helios.cloud.control.model.SettingUnavailable;
import tv.kenley.helios.cloud.control.model.Source;
import tv.kenley.helios.cloud.control.model.StreamNotFound;
import tv.kenley.helios.cloud.control.model.UpdateStreamInput;

class ControlServiceTest {
  static final String ID = DeviceDirectoryTest.ID;
  static final String THING = DeviceDirectoryTest.THING;

  /** Shadows in memory. */
  static final class FakeShadows implements Shadows {
    final Map<String, byte[]> documents = new HashMap<>();

    @Override
    public Optional<byte[]> get(String thingName, String name) {
      return Optional.ofNullable(documents.get(thingName + "/" + name));
    }
  }

  /** The service, with stream commands answered by {@code commands} and a moving clock. */
  static ControlService service(
      DeviceDirectory devices,
      Shadows shadows,
      WifiControlTest.FakeCommands commands,
      SettingsStore settings) {
    WifiControlTest.Moving clock = new WifiControlTest.Moving();
    CommandRunner runner =
        new CommandRunner(commands, clock, duration -> clock.now = clock.now.plus(duration));
    return new ControlService(devices, shadows, new StreamCommands(runner, settings));
  }

  private FakeShadows shadows;
  private DeviceDirectory devices;
  private WifiControlTest.FakeCommands commands;
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
    commands = new WifiControlTest.FakeCommands();
    service = service(devices, shadows, commands, new StreamCommandsTest.MemorySettings());
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
  void switchesRecordingAndReportsIt() {
    streamShadow("stream-shadow-recording.json");
    var listed = service.listStreams(ID);
    var storage = listed.getRecording();
    assertEquals("/var/lib/helios-stream/recordings", storage.getDir());
    assertTrue(storage.isMounted());
    assertEquals(false, storage.isFull());
    assertEquals(118_000_000_000L, storage.getFreeBytes());
    assertEquals(125_371_473_920L, storage.getTotalBytes());
    assertEquals(4, storage.getSegments());
    assertEquals(Instant.parse("2026-11-26T10:25:00Z"), storage.getOldest());

    var pocket = service.getStream(ID, "pocket").getStream();
    var recording = pocket.getReported().getRecording();
    assertEquals(RecordingState.RECORDING, recording.getState());
    assertEquals("pocket/20261126T101500Z-*.ts", recording.getFiles());
    assertEquals("20261126T101500Z-00003.ts", recording.getSegment());
    assertEquals(Instant.parse("2026-11-26T10:15:00Z"), recording.getSince());
    assertNull(pocket.getDesired().isRecord(), "the shadow's desired state isn't shown");
    assertEquals(false, pocket.isPending());

    var backpack = service.getStream(ID, "backpack").getStream();
    assertEquals(RecordingState.IDLE, backpack.getReported().getRecording().getState());
    // A state this contract doesn't know is left out.
    assertNull(service.getStream(ID, "pattern").getStream().getReported().getRecording());

    // Switched with stream.update, and shown as what was set.
    commands.answer(StreamCommandsTest.succeeded(StreamCommandsTest.STATUS));
    var pattern =
        service.update(
            UpdateStreamInput.builder().deviceId(ID).streamId("pattern").record(true).build());
    assertEquals(true, pattern.getStream().getDesired().isRecord());
    // Reported without a recording (an older device).
    streamShadow("stream-shadow-control.json");
    assertNull(service.listStreams(ID).getRecording());
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
    assertTrue(commands.started.isEmpty(), "no command sent");
  }

  @Test
  void refusesAnInvalidDestinationBeforeAnyCommand() {
    streamShadow("stream-shadow-control.json");
    assertThrows(
        InvalidDestination.class,
        () ->
            service.setDestination(
                SetStreamDestinationInput.builder()
                    .deviceId(ID)
                    .streamId("pocket")
                    .url("srt://host:1?mode=listener")
                    .build()));
    assertTrue(commands.started.isEmpty());
  }
}
