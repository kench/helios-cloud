package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tv.kenley.helios.cloud.control.model.BitrateMode;
import tv.kenley.helios.cloud.control.model.BitrateSetting;
import tv.kenley.helios.cloud.control.model.CameraInput;
import tv.kenley.helios.cloud.control.model.CameraSource;
import tv.kenley.helios.cloud.control.model.DesiredState;
import tv.kenley.helios.cloud.control.model.Input;
import tv.kenley.helios.cloud.control.model.ProfileDescription;
import tv.kenley.helios.cloud.control.model.RtmpIngestInput;
import tv.kenley.helios.cloud.control.model.RtmpSource;
import tv.kenley.helios.cloud.control.model.Source;
import tv.kenley.helios.cloud.control.model.StreamDescription;
import tv.kenley.helios.cloud.control.model.StreamState;
import tv.kenley.helios.cloud.control.model.TestSource;

class StreamShadowTest {
  static final String POCKET_URL =
      "srt://ingest.example.com:4001?streamid=publish/live/pocket&latency=2000";

  static byte[] fixture(String name) {
    try (InputStream in = StreamShadowTest.class.getResourceAsStream("/" + name)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  static StreamShadow shadow(String name) {
    return new StreamShadow(ShadowJson.parse(fixture(name)));
  }

  private static Source rtmp(String key) {
    return Source.builder().rtmp(RtmpSource.builder().key(key).build()).build();
  }

  private static Source camera(String video, String audio) {
    CameraSource.Builder camera = CameraSource.builder().video(video);
    if (audio != null) {
      camera.audio(audio);
    }
    return Source.builder().camera(camera.build()).build();
  }

  @Test
  void currentDaemonsStreamHasNoReportedBlockButStartsAndStops() {
    StreamShadow s = shadow("stream-shadow-current.json");
    assertTrue(s.reported());
    assertEquals(41, s.version());
    assertEquals(List.of("pocket"), s.streamIds());
    StreamDescription pocket = s.stream("pocket");
    assertEquals(DesiredState.LIVE, pocket.getDesired().getState());
    assertNull(pocket.getReported(), "no destination reported yet");
    assertFalse(pocket.isPending());
    assertTrue(s.streamWith("pocket", Map.of("state", "stopped")).isPending());
    assertFalse(s.reportsInputs());
    assertFalse(s.reportsProfiles());
    assertTrue(s.offers(rtmp("ingest-9")), "no inputs reported: the device decides");
    assertTrue(s.hasProfile("anything"));
    assertEquals(Optional.empty(), s.destination("pocket"));
  }

  @Test
  void readsDesiredAndReportedState() {
    StreamShadow s = shadow("stream-shadow-control.json");
    assertEquals(42, s.version());
    assertEquals(List.of("backpack", "pattern", "pocket"), s.streamIds());
    StreamDescription pocket = s.stream("pocket");
    assertEquals(DesiredState.LIVE, pocket.getDesired().getState());
    assertEquals(rtmp("ingest-1"), pocket.getDesired().getSource());
    assertEquals("hevc-720p", pocket.getDesired().getProfile());
    assertEquals(POCKET_URL, pocket.getDesired().getDestination());
    assertEquals(
        BitrateSetting.builder().overrideKbps(3000).build(), pocket.getDesired().getBitrate());
    assertEquals(70, pocket.getDesired().getSourceCeilingPercent());

    var reported = pocket.getReported();
    assertEquals(StreamState.LIVE, reported.getState());
    assertEquals(Instant.parse("2026-11-21T22:40:00Z"), reported.getSince());
    assertEquals(rtmp("ingest-1"), reported.getSource());
    assertEquals("hevc", reported.getProfile());
    assertEquals(POCKET_URL, reported.getDestination());
    assertEquals(3000, reported.getBitrateKbps());
    assertEquals(3000, reported.getBitrateOverrideKbps());
    assertEquals(2668, reported.getBitrateCeilingKbps());
    assertEquals(3812, reported.getSourceKbps());
    assertEquals(1, reported.getActiveLinks());
    assertEquals(29.97f, reported.getEncoder().getFps());
    assertEquals(2L, reported.getEncoder().getDupFrames());

    assertEquals(1, pocket.getRejected().size(), "the malformed entry is dropped");
    assertEquals("profile", pocket.getRejected().getFirst().getSetting());
    assertFalse(pocket.isPending(), "the only difference, the profile, was rejected");

    StreamDescription backpack = s.stream("backpack");
    assertEquals(DesiredState.STOPPED, backpack.getDesired().getState());
    assertInstanceOf(BitrateSetting.ProfileDefaultMember.class, backpack.getDesired().getBitrate());
    assertEquals(camera("0bda:5830:CAM1", "0bda:5830:CAM1"), backpack.getReported().getSource());
    assertEquals(2, backpack.getReported().getActiveLinks());
    assertFalse(backpack.isPending());

    StreamDescription pattern = s.stream("pattern");
    assertNull(pattern.getReported(), "no destination or bitrate reported");
    assertEquals(DesiredState.LIVE, pattern.getDesired().getState(), "the device's own");
    assertFalse(pattern.isPending());
    assertTrue(pattern.getRejected() == null || pattern.getRejected().isEmpty());
  }

  @Test
  void pendingFollowsEachDesiredMember() {
    StreamShadow s = shadow("stream-shadow-control.json");
    assertTrue(s.streamWith("pocket", Map.of("destination", "srt://other:1")).isPending());
    assertTrue(s.streamWith("pocket", Map.of("source", Map.of("type", "test"))).isPending());
    assertTrue(s.streamWith("pocket", Map.of("bitrate_kbps", 2500)).isPending());
    assertTrue(s.streamWith("pocket", Map.of("bitrate_kbps", ShadowJson.NULL)).isPending());
    assertFalse(s.streamWith("pocket", Map.of("state", "live")).isPending());
    assertTrue(s.streamWith("backpack", Map.of("profile", "hevc")).isPending());
    assertTrue(s.streamWith("pattern", Map.of("destination", POCKET_URL)).isPending());
    assertTrue(s.streamWith("nope", Map.of("state", "live")).isPending(), "nothing reported");
    assertEquals(Optional.of(POCKET_URL), s.destination("pocket"));
    assertEquals(
        Optional.of("srtla://10.0.0.1:5000?streamid=publish/live/backpack"),
        s.destination("backpack"));
  }

  @Test
  void testPatternIgnoresTheDefaultsTheDeviceFillsIn() {
    StreamShadow s =
        new StreamShadow(
            ShadowJson.parse(
                """
                {"state": {"reported": {"streams": {
                  "bars": {"desired": "stopped",
                           "source": {"type": "test", "size": "1280x720", "fps": 30}},
                  "cam": {"desired": "stopped",
                          "source": {"type": "camera", "video": "usb:1-1", "audio": "usb:1-2",
                                     "product": "C920"}}}}}}
                """
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertFalse(s.streamWith("bars", Map.of("source", Map.of("type", "test"))).isPending());
    assertFalse(
        s.streamWith("bars", Map.of("source", Map.of("type", "test", "fps", 30))).isPending());
    assertTrue(
        s.streamWith("bars", Map.of("source", Map.of("type", "test", "fps", 25))).isPending());
    assertTrue(
        s.streamWith("bars", Map.of("source", Map.of("type", "test", "size", "640x360")))
            .isPending());
    assertTrue(
        s.streamWith("bars", Map.of("source", Map.of("type", "camera", "video", "usb:1-1")))
            .isPending());
    Map<String, Object> both = Map.of("type", "camera", "video", "usb:1-1", "audio", "usb:1-2");
    assertFalse(s.streamWith("cam", Map.of("source", both)).isPending(), "details don't count");
    assertTrue(
        s.streamWith("cam", Map.of("source", Map.of("type", "camera", "video", "usb:1-1")))
            .isPending(),
        "no audio is a different source");
    assertTrue(s.streamWith("cam", Map.of("source", Map.of("type", "test"))).isPending());
  }

  @Test
  void readsInputsAndSkipsWhatItCant() {
    StreamShadow s = shadow("stream-shadow-control.json");
    List<Input> inputs = s.inputs();
    assertEquals(5, inputs.size(), inputs.toString());
    CameraInput cam = ((Input.CameraMember) inputs.get(0)).camera();
    assertEquals("0bda:5830:CAM1", cam.getSelector());
    assertEquals("1-1.3", cam.getPort());
    assertEquals("Realtek", cam.getManufacturer());
    assertTrue(cam.isHasAudio());
    assertEquals(1, cam.getFormats().size(), "the incomplete format is dropped");
    assertEquals(List.of(30f, 15f), cam.getFormats().getFirst().getFps());
    assertInstanceOf(Input.AudioMember.class, inputs.get(1));
    RtmpIngestInput ingest = ((Input.RtmpMember) inputs.get(2)).rtmp();
    assertNull(ingest.getPublisher(), "a publisher without since is left out");
    RtmpIngestInput second = ((Input.RtmpMember) inputs.get(3)).rtmp();
    assertEquals("rtmp://192.168.5.31:1935/live/ingest-1", second.getPublishUrl());
    assertEquals(3812, second.getPublisher().getVideoKbps());
    assertEquals("H264", second.getPublisher().getVideoCodec());
    assertInstanceOf(Input.TestMember.class, inputs.get(4));
  }

  @Test
  void checksSourcesAgainstTheInputs() {
    StreamShadow s = shadow("stream-shadow-control.json");
    assertTrue(s.offers(camera("0bda:5830:CAM1", "0bda:5830:CAM1")));
    assertTrue(s.offers(camera("0bda:5830:CAM1", "usb:1-1.4")));
    assertTrue(s.offers(camera("0bda:5830:CAM1", null)));
    assertFalse(s.offers(camera("0bda:5830:CAM1", "usb:9-9")));
    assertFalse(s.offers(camera("usb:2-1", null)));
    assertTrue(s.offers(rtmp("ingest")));
    assertFalse(s.offers(rtmp("ingest-2")));
    assertTrue(s.offers(Source.builder().test(TestSource.builder().build()).build()));
  }

  @Test
  void readsProfilesAndSkipsUnknownModes() {
    StreamShadow s = shadow("stream-shadow-control.json");
    List<ProfileDescription> profiles = s.profiles();
    assertEquals(
        List.of("cam", "hevc"), profiles.stream().map(ProfileDescription::getName).toList());
    assertEquals("1280x720", profiles.get(0).getScale());
    assertNull(profiles.get(1).getScale(), "an empty scale is none");
    assertEquals(BitrateMode.ADAPTIVE, profiles.get(1).getBitrateMode());
    assertTrue(s.hasProfile("hevc"));
    assertFalse(s.hasProfile("odd-not-there"));
  }

  @Test
  void convertsSourcesBothWays() {
    for (Source source :
        List.of(
            rtmp("ingest"),
            rtmp("ingest-3"),
            camera("usb:1-1.3", null),
            camera("0bda:5830:CAM1", "usb:1-1.4"),
            Source.builder().test(TestSource.builder().size("640x360").fps(25).build()).build(),
            Source.builder().test(TestSource.builder().build()).build())) {
      assertEquals(Optional.of(source), StreamShadow.sourceOf(StreamShadow.sourceFields(source)));
    }
    assertEquals(Map.of("type", "rtmp", "camera", 3), StreamShadow.sourceFields(rtmp("ingest-3")));
    assertEquals(Optional.empty(), StreamShadow.sourceOf(Map.of("type", "camera")));
    assertEquals(Optional.empty(), StreamShadow.sourceOf(Map.of("type", "hdmi")));
    assertThrows(
        IllegalArgumentException.class,
        () -> StreamShadow.sourceFields(Source.builder().$unknownMember("x").build()));
    assertFalse(new StreamShadow(null).reported());
    assertEquals(0, new StreamShadow(null).version());
  }
}
