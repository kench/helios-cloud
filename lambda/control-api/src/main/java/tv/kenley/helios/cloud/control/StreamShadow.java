package tv.kenley.helios.cloud.control;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import software.amazon.smithy.java.core.schema.Unit;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.AudioInput;
import tv.kenley.helios.cloud.control.model.BitrateMode;
import tv.kenley.helios.cloud.control.model.BitrateSetting;
import tv.kenley.helios.cloud.control.model.CameraInput;
import tv.kenley.helios.cloud.control.model.CameraSource;
import tv.kenley.helios.cloud.control.model.DesiredState;
import tv.kenley.helios.cloud.control.model.EncoderFigures;
import tv.kenley.helios.cloud.control.model.Input;
import tv.kenley.helios.cloud.control.model.ProfileDescription;
import tv.kenley.helios.cloud.control.model.RecordingState;
import tv.kenley.helios.cloud.control.model.RecordingStorage;
import tv.kenley.helios.cloud.control.model.RtmpIngestInput;
import tv.kenley.helios.cloud.control.model.RtmpPublisher;
import tv.kenley.helios.cloud.control.model.RtmpSource;
import tv.kenley.helios.cloud.control.model.Source;
import tv.kenley.helios.cloud.control.model.StreamDescription;
import tv.kenley.helios.cloud.control.model.StreamDesired;
import tv.kenley.helios.cloud.control.model.StreamRecording;
import tv.kenley.helios.cloud.control.model.StreamReported;
import tv.kenley.helios.cloud.control.model.StreamState;
import tv.kenley.helios.cloud.control.model.TestPatternInput;
import tv.kenley.helios.cloud.control.model.TestSource;
import tv.kenley.helios.cloud.control.model.VideoFormat;

/**
 * A device's {@code stream} shadow, read into the Control API's types. The shadow is reported-only
 * (the page "Helios - State Management: Tenets and Practices"): changes are commands ({@link
 * StreamCommands}). Field names are the stream daemon's. Anything missing or of the wrong type is
 * left out rather than failing the request, so a device whose Helios predates a field still works
 * for the rest.
 *
 * @param document the whole shadow document ({@code state}, {@code version}...), or {@code null} if
 *     the device has none
 */
record StreamShadow(Document document) {
  /** Whether the device has reported its streams at all. */
  boolean reported() {
    return ShadowJson.at(document, "state", "reported", "streams").isPresent();
  }

  /** The shadow's version, 0 if there's no shadow. */
  long version() {
    return ShadowJson.whole(document, "version").orElse(0L);
  }

  /** The IDs of the streams the device reports, sorted. */
  List<String> streamIds() {
    return new ArrayList<>(
        new TreeSet<>(ShadowJson.map(document, "state", "reported", "streams").keySet()));
  }

  /** Whether the device reports a stream {@code id}. */
  boolean hasStream(String id) {
    return ShadowJson.at(document, "state", "reported", "streams", id).isPresent();
  }

  /** The destination the device reports for stream {@code id}. */
  Optional<String> reportedDestination(String id) {
    return reportedStream(id).flatMap(r -> ShadowJson.string(r, "destination"));
  }

  // --- What this API set ------------------------------------------------------------------------

  /**
   * Settings in shadow form ({@code null}s as ShadowJson.NULL) from the settings store's record of
   * a stream: state, bitrate_kbps, source, profile, destination, source_ceiling_percent, record.
   */
  static Map<String, Object> settingsOf(Document block) {
    Map<String, Object> desired = new LinkedHashMap<>();
    Optional<Document> doc = Optional.of(block);
    ShadowJson.string(doc.get(), "state").ifPresent(v -> desired.put("state", v));
    if (ShadowJson.has(doc.get(), "bitrate_kbps")) {
      desired.put(
          "bitrate_kbps",
          ShadowJson.integer(doc.get(), "bitrate_kbps")
              .<Object>map(v -> v)
              .orElse(ShadowJson.NULL));
    }
    ShadowJson.at(doc.get(), "source")
        .map(StreamShadow::sourceFields)
        .ifPresent(v -> desired.put("source", v));
    ShadowJson.string(doc.get(), "profile").ifPresent(v -> desired.put("profile", v));
    ShadowJson.string(doc.get(), "destination").ifPresent(v -> desired.put("destination", v));
    ShadowJson.integer(doc.get(), "source_ceiling_percent")
        .ifPresent(v -> desired.put("source_ceiling_percent", v));
    ShadowJson.bool(doc.get(), "record").ifPresent(v -> desired.put("record", v));
    return desired;
  }

  /**
   * Stream {@code id}: {@code desired} is what this API last set (the settings store's record, in
   * shadow form); reported is {@code status} (a command's answer) when given, else the shadow's.
   * The contract's deprecated {@code pending} is always false, and {@code rejected} is never set: a
   * command answers refusals itself.
   */
  // `pending` is deprecated in the contract but still required.
  @SuppressWarnings("deprecation")
  StreamDescription commanded(String id, Map<String, Object> desired, Optional<Document> status) {
    final Optional<Document> reportedDoc = status.or(() -> reportedStream(id));
    StreamDesired.Builder desiredShape = StreamDesired.builder();
    String state =
        (String)
            desired.getOrDefault(
                "state",
                reportedDoc.flatMap(r -> ShadowJson.string(r, "desired")).orElse("stopped"));
    desiredShape.state(DesiredState.from(state.equals("live") ? "live" : "stopped"));
    if (desired.get("source") instanceof Map<?, ?> source) {
      sourceOf(source).ifPresent(desiredShape::source);
    }
    if (desired.get("profile") instanceof String profile) {
      desiredShape.profile(profile);
    }
    if (desired.get("destination") instanceof String destination) {
      desiredShape.destination(destination);
    }
    if (desired.containsKey("bitrate_kbps")) {
      desiredShape.bitrate(
          desired.get("bitrate_kbps") instanceof Integer kbps
              ? BitrateSetting.builder().overrideKbps(kbps).build()
              : BitrateSetting.builder().profileDefault(Unit.getInstance()).build());
    }
    if (desired.get("source_ceiling_percent") instanceof Integer percent) {
      desiredShape.sourceCeilingPercent(percent);
    }
    if (desired.get("record") instanceof Boolean record) {
      desiredShape.record(record);
    }

    StreamDescription.Builder stream =
        StreamDescription.builder().streamId(id).desired(desiredShape.build()).pending(false);
    reportedDoc.flatMap(this::reportedOf).ifPresent(stream::reported);
    return stream.build();
  }

  // --- Reading reported state ------------------------------------------------------------------

  private Optional<Document> reportedStream(String id) {
    return ShadowJson.at(document, "state", "reported", "streams", id);
  }

  /** The reported block, if the device reports everything it must (a destination among them). */
  private Optional<StreamReported> reportedOf(Document r) {
    Optional<StreamState> state =
        ShadowJson.string(r, "state")
            .filter(s -> StreamState.values().stream().anyMatch(k -> k.getValue().equals(s)))
            .map(StreamState::from);
    Optional<Instant> since = ShadowJson.string(r, "since").flatMap(StreamShadow::instant);
    Optional<Source> source =
        ShadowJson.at(r, "source").map(StreamShadow::sourceFields).flatMap(StreamShadow::sourceOf);
    Optional<String> profile = ShadowJson.string(r, "profile");
    Optional<String> destination = ShadowJson.string(r, "destination");
    Optional<Integer> bitrate = ShadowJson.integer(r, "bitrate_kbps");
    if (state.isEmpty()
        || since.isEmpty()
        || source.isEmpty()
        || profile.isEmpty()
        || destination.isEmpty()
        || bitrate.isEmpty()) {
      return Optional.empty();
    }
    StreamReported.Builder reported =
        StreamReported.builder()
            .state(state.get())
            .since(since.get())
            .source(source.get())
            .profile(profile.get())
            .destination(destination.get())
            .bitrateKbps(bitrate.get());
    ShadowJson.integer(r, "bitrate_override_kbps").ifPresent(reported::bitrateOverrideKbps);
    ShadowJson.integer(r, "bitrate_ceiling_kbps").ifPresent(reported::bitrateCeilingKbps);
    ShadowJson.integer(r, "source_kbps").ifPresent(reported::sourceKbps);
    ShadowJson.string(r, "last_error").ifPresent(reported::lastError);
    ShadowJson.at(r, "encoder")
        .filter(ShadowJson::isMap)
        .map(StreamShadow::encoderOf)
        .ifPresent(reported::encoder);
    ShadowJson.string(r, "output")
        .flatMap(output ->
            ShadowJson.integer(document, "state", "reported", "outputs", output, "active_links"))
        .ifPresent(reported::activeLinks);
    ShadowJson.at(r, "recording")
        .filter(ShadowJson::isMap)
        .flatMap(StreamShadow::recordingOf)
        .ifPresent(reported::recording);
    return Optional.of(reported.build());
  }

  /** A stream's recording, if its state is one the contract knows. */
  private static Optional<StreamRecording> recordingOf(Document d) {
    Optional<RecordingState> state =
        ShadowJson.string(d, "state")
            .filter(s -> RecordingState.values().stream().anyMatch(k -> k.getValue().equals(s)))
            .map(RecordingState::from);
    if (state.isEmpty()) {
      return Optional.empty();
    }
    StreamRecording.Builder recording = StreamRecording.builder().state(state.get());
    ShadowJson.string(d, "files").ifPresent(recording::files);
    ShadowJson.string(d, "segment").ifPresent(recording::segment);
    ShadowJson.string(d, "since").flatMap(StreamShadow::instant).ifPresent(recording::since);
    ShadowJson.string(d, "last_error").ifPresent(recording::lastError);
    return Optional.of(recording.build());
  }

  /** The device's recordings storage, if it reports one (Helios 0.15.0 and later). */
  Optional<RecordingStorage> recordingStorage() {
    Optional<Document> doc =
        ShadowJson.at(document, "state", "reported", "recording").filter(ShadowJson::isMap);
    if (doc.isEmpty()) {
      return Optional.empty();
    }
    Document d = doc.get();
    Optional<String> dir = ShadowJson.string(d, "dir");
    Optional<Boolean> mounted = ShadowJson.bool(d, "mounted");
    Optional<Boolean> full = ShadowJson.bool(d, "full");
    if (dir.isEmpty() || mounted.isEmpty() || full.isEmpty()) {
      return Optional.empty();
    }
    RecordingStorage.Builder storage =
        RecordingStorage.builder().dir(dir.get()).mounted(mounted.get()).full(full.get());
    ShadowJson.whole(d, "total_bytes").ifPresent(storage::totalBytes);
    ShadowJson.whole(d, "free_bytes").ifPresent(storage::freeBytes);
    ShadowJson.integer(d, "segments").ifPresent(storage::segments);
    ShadowJson.string(d, "oldest").flatMap(StreamShadow::instant).ifPresent(storage::oldest);
    ShadowJson.string(d, "last_error").ifPresent(storage::lastError);
    return Optional.of(storage.build());
  }

  private static EncoderFigures encoderOf(Document e) {
    EncoderFigures.Builder encoder = EncoderFigures.builder();
    ShadowJson.number(e, "fps").ifPresent(v -> encoder.fps(v.floatValue()));
    ShadowJson.number(e, "bitrate_kbps").ifPresent(v -> encoder.bitrateKbps(v.floatValue()));
    ShadowJson.whole(e, "drop_frames").ifPresent(encoder::dropFrames);
    ShadowJson.whole(e, "dup_frames").ifPresent(encoder::dupFrames);
    ShadowJson.number(e, "speed").ifPresent(v -> encoder.speed(v.floatValue()));
    return encoder.build();
  }

  // --- Sources, in shadow form ({"type": ...}) and as the API's union -------------------------

  /**
   * A source's identifying fields in shadow form (the device adds the camera's details to its
   * reported source; those are left out).
   */
  static Map<String, Object> sourceFields(Document source) {
    Map<String, Object> fields = new LinkedHashMap<>();
    String type = ShadowJson.string(source, "type").orElse("");
    fields.put("type", type);
    switch (type) {
      case "camera" -> {
        ShadowJson.string(source, "video").ifPresent(v -> fields.put("video", v));
        ShadowJson.string(source, "audio").ifPresent(v -> fields.put("audio", v));
      }
      case "rtmp" -> ShadowJson.integer(source, "camera").ifPresent(v -> fields.put("camera", v));
      case "test" -> {
        ShadowJson.string(source, "size").ifPresent(v -> fields.put("size", v));
        ShadowJson.integer(source, "fps").ifPresent(v -> fields.put("fps", v));
      }
      default -> {}
    }
    return fields;
  }

  /** The shadow form of the API's {@code source}. */
  static Map<String, Object> sourceFields(Source source) {
    Map<String, Object> fields = new LinkedHashMap<>();
    switch (source) {
      case Source.CameraMember(CameraSource camera) -> {
        fields.put("type", "camera");
        fields.put("video", camera.getVideo());
        if (camera.getAudio() != null) {
          fields.put("audio", camera.getAudio());
        }
      }
      case Source.RtmpMember(RtmpSource rtmp) -> {
        fields.put("type", "rtmp");
        if (rtmp.getKey().startsWith("ingest-")) {
          fields.put("camera", Integer.parseInt(rtmp.getKey().substring("ingest-".length())));
        }
      }
      case Source.TestMember(TestSource test) -> {
        fields.put("type", "test");
        if (test.getSize() != null) {
          fields.put("size", test.getSize());
        }
        if (test.getFps() != null) {
          fields.put("fps", test.getFps());
        }
      }
      default -> throw new IllegalArgumentException("unknown source " + source);
    }
    return fields;
  }

  /** {@code source} as a stream command takes it: the Control API's union, snake_case. */
  static Map<String, Object> sourceArg(Source source) {
    Map<String, Object> inner = new LinkedHashMap<>();
    String kind;
    switch (source) {
      case Source.CameraMember(CameraSource camera) -> {
        kind = "camera";
        inner.put("video", camera.getVideo());
        if (camera.getAudio() != null) {
          inner.put("audio", camera.getAudio());
        }
      }
      case Source.RtmpMember(RtmpSource rtmp) -> {
        kind = "rtmp";
        inner.put("key", rtmp.getKey());
      }
      case Source.TestMember(TestSource test) -> {
        kind = "test";
        if (test.getSize() != null) {
          inner.put("size", test.getSize());
        }
        if (test.getFps() != null) {
          inner.put("fps", test.getFps());
        }
      }
      default -> throw new IllegalArgumentException("unknown source " + source);
    }
    return Map.of(kind, inner);
  }

  /** The API's source for {@code fields} (shadow form), if it's one the API knows. */
  static Optional<Source> sourceOf(Map<?, ?> fields) {
    Object type = fields.get("type");
    if ("camera".equals(type) && fields.get("video") instanceof String video) {
      CameraSource.Builder camera = CameraSource.builder().video(video);
      if (fields.get("audio") instanceof String audio) {
        camera.audio(audio);
      }
      return Optional.of(Source.builder().camera(camera.build()).build());
    }
    if ("rtmp".equals(type)) {
      String key = fields.get("camera") instanceof Integer n ? "ingest-" + n : "ingest";
      return Optional.of(Source.builder().rtmp(RtmpSource.builder().key(key).build()).build());
    }
    if ("test".equals(type)) {
      TestSource.Builder test = TestSource.builder();
      if (fields.get("size") instanceof String size) {
        test.size(size);
      }
      if (fields.get("fps") instanceof Integer fps) {
        test.fps(fps);
      }
      return Optional.of(Source.builder().test(test.build()).build());
    }
    return Optional.empty();
  }

  // --- Inputs and profiles ---------------------------------------------------------------------

  /** Whether the device reports its inputs (a newer stream daemon does). */
  boolean reportsInputs() {
    return ShadowJson.at(document, "state", "reported", "inputs").isPresent();
  }

  /** The inputs the device reports, with each RTMP key's current publisher. */
  List<Input> inputs() {
    List<Input> inputs = new ArrayList<>();
    for (Document input : ShadowJson.list(document, "state", "reported", "inputs")) {
      String type = ShadowJson.string(input, "type").orElse("");
      switch (type) {
        case "camera" ->
            cameraOf(input).ifPresent(c -> inputs.add(Input.builder().camera(c).build()));
        case "audio" -> audioOf(input).ifPresent(a -> inputs.add(Input.builder().audio(a).build()));
        case "rtmp" -> rtmpOf(input).ifPresent(r -> inputs.add(Input.builder().rtmp(r).build()));
        case "test" -> inputs.add(Input.builder().test(TestPatternInput.builder().build()).build());
        default -> {}
      }
    }
    return inputs;
  }

  private static Optional<CameraInput> cameraOf(Document input) {
    Optional<String> selector = ShadowJson.string(input, "selector");
    Optional<String> port = ShadowJson.string(input, "port");
    if (selector.isEmpty() || port.isEmpty()) {
      return Optional.empty();
    }
    CameraInput.Builder camera =
        CameraInput.builder()
            .selector(selector.get())
            .port(port.get())
            .hasAudio(ShadowJson.bool(input, "has_audio").orElse(false));
    ShadowJson.string(input, "product").ifPresent(camera::product);
    ShadowJson.string(input, "manufacturer").ifPresent(camera::manufacturer);
    if (ShadowJson.at(input, "formats").isPresent()) {
      camera.formats(
          ShadowJson.list(input, "formats").stream()
              .map(StreamShadow::formatOf)
              .flatMap(Optional::stream)
              .toList());
    }
    return Optional.of(camera.build());
  }

  private static Optional<VideoFormat> formatOf(Document format) {
    Optional<String> pixel = ShadowJson.string(format, "pixel_format");
    Optional<Integer> width = ShadowJson.integer(format, "width");
    Optional<Integer> height = ShadowJson.integer(format, "height");
    if (pixel.isEmpty() || width.isEmpty() || height.isEmpty()) {
      return Optional.empty();
    }
    List<Float> fps =
        ShadowJson.list(format, "fps").stream()
            .filter(d -> ShadowJson.number(d).isPresent())
            .map(d -> d.asNumber().floatValue())
            .toList();
    return Optional.of(
        VideoFormat.builder()
            .pixelFormat(pixel.get())
            .width(width.get())
            .height(height.get())
            .fps(fps)
            .build());
  }

  private static Optional<AudioInput> audioOf(Document input) {
    Optional<String> selector = ShadowJson.string(input, "selector");
    Optional<String> port = ShadowJson.string(input, "port");
    if (selector.isEmpty() || port.isEmpty()) {
      return Optional.empty();
    }
    AudioInput.Builder audio = AudioInput.builder().selector(selector.get()).port(port.get());
    ShadowJson.string(input, "product").ifPresent(audio::product);
    ShadowJson.string(input, "manufacturer").ifPresent(audio::manufacturer);
    return Optional.of(audio.build());
  }

  private Optional<RtmpIngestInput> rtmpOf(Document input) {
    Optional<String> key = ShadowJson.string(input, "key");
    if (key.isEmpty()) {
      return Optional.empty();
    }
    RtmpIngestInput.Builder rtmp = RtmpIngestInput.builder().key(key.get());
    ShadowJson.string(input, "publish_url").ifPresent(rtmp::publishUrl);
    ShadowJson.at(document, "state", "reported", "rtmp_ingest", "publishers", key.get())
        .flatMap(StreamShadow::publisherOf)
        .ifPresent(rtmp::publisher);
    return Optional.of(rtmp.build());
  }

  private static Optional<RtmpPublisher> publisherOf(Document p) {
    Optional<String> address = ShadowJson.string(p, "address");
    Optional<Instant> since = ShadowJson.string(p, "since").flatMap(StreamShadow::instant);
    if (address.isEmpty() || since.isEmpty()) {
      return Optional.empty();
    }
    RtmpPublisher.Builder publisher =
        RtmpPublisher.builder().address(address.get()).since(since.get());
    ShadowJson.string(p, "video_codec").ifPresent(publisher::videoCodec);
    ShadowJson.integer(p, "width").ifPresent(publisher::width);
    ShadowJson.integer(p, "height").ifPresent(publisher::height);
    ShadowJson.integer(p, "video_kbps").ifPresent(publisher::videoKbps);
    return Optional.of(publisher.build());
  }

  /** Whether the device reports {@code source} among its inputs (or reports no inputs). */
  boolean offers(Source source) {
    if (!reportsInputs()) {
      return true;
    }
    List<Input> inputs = inputs();
    return switch (source) {
      case Source.CameraMember(CameraSource camera) ->
          inputs.stream()
                  .anyMatch(i ->
                      i instanceof Input.CameraMember(CameraInput c)
                          && c.getSelector().equals(camera.getVideo()))
              && (camera.getAudio() == null
                  || inputs.stream()
                      .anyMatch(i ->
                          (i instanceof Input.CameraMember(CameraInput c)
                                  && c.isHasAudio()
                                  && c.getSelector().equals(camera.getAudio()))
                              || (i instanceof Input.AudioMember(AudioInput a)
                                  && a.getSelector().equals(camera.getAudio()))));
      case Source.RtmpMember(RtmpSource rtmp) ->
          inputs.stream()
              .anyMatch(i ->
                  i instanceof Input.RtmpMember(RtmpIngestInput r)
                      && r.getKey().equals(rtmp.getKey()));
      case Source.TestMember test -> inputs.stream().anyMatch(i -> i instanceof Input.TestMember);
      default -> false;
    };
  }

  /** Whether the device reports its profiles. */
  boolean reportsProfiles() {
    return ShadowJson.at(document, "state", "reported", "profiles").isPresent();
  }

  /** Whether the device has profile {@code name} (or reports no profiles). */
  boolean hasProfile(String name) {
    return !reportsProfiles()
        || ShadowJson.at(document, "state", "reported", "profiles", name).isPresent();
  }

  /** The profiles the device reports, by name. */
  List<ProfileDescription> profiles() {
    List<ProfileDescription> profiles = new ArrayList<>();
    for (Map.Entry<String, Document> entry :
        new java.util.TreeMap<>(ShadowJson.map(document, "state", "reported", "profiles"))
            .entrySet()) {
      profileOf(entry.getKey(), entry.getValue()).ifPresent(profiles::add);
    }
    return profiles;
  }

  private static Optional<ProfileDescription> profileOf(String name, Document p) {
    Optional<String> video = ShadowJson.string(p, "video");
    Optional<String> encoder = ShadowJson.string(p, "encoder");
    Optional<String> mode =
        ShadowJson.string(p, "bitrate_mode")
            .filter(m -> BitrateMode.values().stream().anyMatch(k -> k.getValue().equals(m)));
    Optional<Integer> bitrate = ShadowJson.integer(p, "bitrate_kbps");
    Optional<Integer> min = ShadowJson.integer(p, "min_bitrate_kbps");
    Optional<Integer> max = ShadowJson.integer(p, "max_bitrate_kbps");
    Optional<String> audio = ShadowJson.string(p, "audio");
    if (video.isEmpty()
        || encoder.isEmpty()
        || mode.isEmpty()
        || bitrate.isEmpty()
        || min.isEmpty()
        || max.isEmpty()
        || audio.isEmpty()) {
      return Optional.empty();
    }
    ProfileDescription.Builder profile =
        ProfileDescription.builder()
            .name(name)
            .video(video.get())
            .encoder(encoder.get())
            .bitrateMode(BitrateMode.from(mode.get()))
            .bitrateKbps(bitrate.get())
            .minBitrateKbps(min.get())
            .maxBitrateKbps(max.get())
            .audio(audio.get());
    ShadowJson.string(p, "scale").filter(s -> !s.isEmpty()).ifPresent(profile::scale);
    return Optional.of(profile.build());
  }

  private static Optional<Instant> instant(String text) {
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
