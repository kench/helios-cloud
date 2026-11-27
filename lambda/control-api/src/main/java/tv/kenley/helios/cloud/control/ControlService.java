package tv.kenley.helios.cloud.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.BitrateSetting;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.GetDeviceLocationOutput;
import tv.kenley.helios.cloud.control.model.GetDeviceOutput;
import tv.kenley.helios.cloud.control.model.GetDeviceUplinksOutput;
import tv.kenley.helios.cloud.control.model.GetStreamOutput;
import tv.kenley.helios.cloud.control.model.ListDevicesOutput;
import tv.kenley.helios.cloud.control.model.ListInputsOutput;
import tv.kenley.helios.cloud.control.model.ListProfilesOutput;
import tv.kenley.helios.cloud.control.model.ListStreamsOutput;
import tv.kenley.helios.cloud.control.model.RefusedSetting;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationInput;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationOutput;
import tv.kenley.helios.cloud.control.model.SettingUnavailable;
import tv.kenley.helios.cloud.control.model.StartStreamOutput;
import tv.kenley.helios.cloud.control.model.StopStreamOutput;
import tv.kenley.helios.cloud.control.model.StreamChangeRefused;
import tv.kenley.helios.cloud.control.model.StreamDescription;
import tv.kenley.helios.cloud.control.model.StreamNotFound;
import tv.kenley.helios.cloud.control.model.UpdateStreamInput;
import tv.kenley.helios.cloud.control.model.UpdateStreamOutput;

/**
 * The Control API's operations (HLD section 7.3): devices from the devices table, everything else
 * from the device's shadows. Stream changes are commands ({@link StreamCommands}; the page "Helios
 * - State Management: Tenets and Practices"): applied before the response, and recorded for
 * GetStream's {@code desired}. Shadows are read, never written for a change. A device whose Helios
 * predates the stream commands (before 0.16.0) answers that it can't take them.
 */
final class ControlService {
  static final String STREAM_SHADOW = "stream";
  static final String HELIOS_SHADOW = "helios";
  static final String GPS_SHADOW = "gps";
  static final String NET_SHADOW = "net";
  static final int DEFAULT_PAGE = 50;

  private final DeviceDirectory devices;
  private final Shadows shadows;
  private final StreamCommands streams;

  ControlService(DeviceDirectory devices, Shadows shadows, StreamCommands streams) {
    this.devices = devices;
    this.shadows = shadows;
    this.streams = streams;
  }

  ListDevicesOutput listDevices(String nextToken, Integer maxResults) {
    DeviceDirectory.Page page =
        devices.list(nextToken, maxResults == null ? DEFAULT_PAGE : maxResults);
    ListDevicesOutput.Builder output = ListDevicesOutput.builder().devices(page.devices());
    if (page.nextToken() != null) {
      output.nextToken(page.nextToken());
    }
    return output.build();
  }

  GetDeviceOutput getDevice(String deviceId) {
    DeviceSummary device = device(deviceId);
    GetDeviceOutput.Builder output = GetDeviceOutput.builder().device(device);
    Optional<Document> helios =
        shadows.get(device.getThingName(), HELIOS_SHADOW).map(ShadowJson::parse);
    if (helios.isPresent()) {
      Map<String, String> modules = new java.util.TreeMap<>();
      ShadowJson.map(helios.get(), "state", "reported", "modules")
          .forEach((name, status) ->
              ShadowJson.string(status, "state").ifPresent(state -> modules.put(name, state)));
      output.modules(modules);
    }
    return output.build();
  }

  ListInputsOutput listInputs(String deviceId) {
    StreamShadow shadow = reportedShadow(deviceId);
    if (!shadow.reportsInputs()) {
      throw unavailable(
          "The device doesn't report its inputs (its Helios predates the Control API).");
    }
    return ListInputsOutput.builder().inputs(shadow.inputs()).build();
  }

  ListProfilesOutput listProfiles(String deviceId) {
    StreamShadow shadow = reportedShadow(deviceId);
    if (!shadow.reportsProfiles()) {
      throw unavailable(
          "The device doesn't report its profiles (its Helios predates the Control API).");
    }
    return ListProfilesOutput.builder().profiles(shadow.profiles()).build();
  }

  /** The device's gps fix (LLD 10). Its exact position: never logged. */
  GetDeviceLocationOutput getDeviceLocation(String deviceId) {
    GpsShadow shadow =
        new GpsShadow(
            shadows
                .get(device(deviceId).getThingName(), GPS_SHADOW)
                .map(ShadowJson::parse)
                .orElse(null));
    if (!shadow.reported()) {
      throw unavailable(
          "The device hasn't reported a gps state (no gps module, or a Helios before 0.8.0).");
    }
    return shadow.location();
  }

  /**
   * The device's interfaces and what its SRTLA bonds do with them (LLD 3). Public addresses only
   * when {@code includePublicAddresses}; they're never logged.
   */
  GetDeviceUplinksOutput getDeviceUplinks(String deviceId, boolean includePublicAddresses) {
    String thing = device(deviceId).getThingName();
    UplinksShadow shadow =
        new UplinksShadow(
            shadows.get(thing, NET_SHADOW).map(ShadowJson::parse).orElse(null),
            shadows.get(thing, STREAM_SHADOW).map(ShadowJson::parse).orElse(null));
    if (!shadow.reported()) {
      throw unavailable(
          "The device hasn't reported its interfaces (no net module, or a Helios before 0.5.0).");
    }
    return shadow.uplinks(includePublicAddresses);
  }

  ListStreamsOutput listStreams(String deviceId) {
    StreamShadow shadow = reportedShadow(deviceId);
    DeviceSummary device = device(deviceId);
    ListStreamsOutput.Builder output =
        ListStreamsOutput.builder()
            .streams(
                shadow.streamIds().stream()
                    .map(id -> streams.describe(device, shadow, id))
                    .toList());
    shadow.recordingStorage().ifPresent(output::recording);
    return output.build();
  }

  GetStreamOutput getStream(String deviceId, String streamId) {
    StreamShadow shadow = existing(deviceId, streamId);
    DeviceSummary device = device(deviceId);
    return GetStreamOutput.builder().stream(streams.describe(device, shadow, streamId)).build();
  }

  StartStreamOutput start(String deviceId, String streamId) {
    Change change =
        command(deviceId, streamId, StreamCommands.START, Map.of(), Map.of("state", "live"));
    return StartStreamOutput.builder().stream(change.stream())
        .shadowVersion(change.version())
        .build();
  }

  StopStreamOutput stop(String deviceId, String streamId) {
    Change change =
        command(deviceId, streamId, StreamCommands.STOP, Map.of(), Map.of("state", "stopped"));
    return StopStreamOutput.builder().stream(change.stream())
        .shadowVersion(change.version())
        .build();
  }

  /**
   * Source, profile, bitrate, ceiling and recording, each only if given.
   *
   * @throws SettingUnavailable if the device reports its inputs or profiles and doesn't have the
   *     source or profile asked for
   */
  UpdateStreamOutput update(UpdateStreamInput input) {
    StreamShadow shadow = existing(input.getDeviceId(), input.getStreamId());
    Map<String, Object> record = new LinkedHashMap<>();
    if (input.getSource() != null) {
      if (!shadow.offers(input.getSource())) {
        throw SettingUnavailable.builder()
            .setting("source")
            .message("The device doesn't report this source among its inputs.")
            .build();
      }
      record.put("source", StreamShadow.sourceFields(input.getSource()));
    }
    if (input.getProfile() != null) {
      if (!shadow.hasProfile(input.getProfile())) {
        throw SettingUnavailable.builder()
            .setting("profile")
            .message("The device has no profile " + input.getProfile() + ".")
            .build();
      }
      record.put("profile", input.getProfile());
    }
    if (input.getBitrate() != null) {
      record.put(
          "bitrate_kbps",
          input.getBitrate() instanceof BitrateSetting.OverrideKbpsMember(int kbps)
              ? (Object) kbps
              : ShadowJson.NULL);
    }
    if (input.getSourceCeilingPercent() != null) {
      record.put("source_ceiling_percent", input.getSourceCeilingPercent());
    }
    if (input.isRecord() != null) {
      record.put("record", input.isRecord());
    }
    Change change =
        command(
            input.getDeviceId(),
            input.getStreamId(),
            StreamCommands.UPDATE,
            updateArgs(input),
            record);
    return UpdateStreamOutput.builder().stream(change.stream())
        .shadowVersion(change.version())
        .build();
  }

  /**
   * Checks the URL and sets it, in its normal form and sealed to the device's key.
   *
   * @throws tv.kenley.helios.cloud.control.model.InvalidDestination if it isn't an SRT or SRTLA URL
   *     within the contract's rules
   */
  SetStreamDestinationOutput setDestination(SetStreamDestinationInput input) {
    String url = Destinations.parse(input.getUrl()).url();
    StreamShadow shadow = existing(input.getDeviceId(), input.getStreamId());
    DeviceSummary device = device(input.getDeviceId());
    final Optional<String> previous =
        Optional.ofNullable(
                streams.recorded(device.getDeviceId(), input.getStreamId()).get("destination"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .or(() -> shadow.reportedDestination(input.getStreamId()));
    String key =
        Sealing.deviceKey(shadows, device.getThingName())
            .orElseThrow(() ->
                StreamChangeRefused.builder()
                    .message("The device doesn't report a key to seal the destination to.")
                    .refused(
                        java.util.List.of(
                            RefusedSetting.builder()
                                .setting("destination")
                                .reason("no key to seal it to")
                                .build()))
                    .build());
    Map<String, Object> sealed =
        Sealing.sealedArg(
            key,
            device.getThingName(),
            "stream.destination " + input.getStreamId(),
            url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Change change =
        command(
            input.getDeviceId(),
            input.getStreamId(),
            StreamCommands.UPDATE,
            Map.of("destination", Map.of("set", sealed)),
            Map.of("destination", url));
    SetStreamDestinationOutput.Builder output =
        SetStreamDestinationOutput.builder().stream(change.stream())
            .shadowVersion(change.version());
    previous.ifPresent(output::previous);
    return output.build();
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private record Change(StreamDescription stream, long version) {}

  /** Runs a stream command with {@code args} (besides the stream) and records {@code changes}. */
  private Change command(
      String deviceId,
      String streamId,
      String commandId,
      Map<String, Object> args,
      Map<String, Object> changes) {
    Map<String, Object> all = new LinkedHashMap<>();
    all.put("stream_id", streamId);
    all.putAll(args);
    StreamCommands.Applied applied =
        streams.apply(
            device(deviceId), existing(deviceId, streamId), streamId, commandId, all, changes);
    return new Change(applied.stream(), applied.version());
  }

  /** UpdateStream's settings as stream.update takes them: each {"set": v} or {"configured": {}}. */
  private static Map<String, Object> updateArgs(UpdateStreamInput input) {
    Map<String, Object> args = new LinkedHashMap<>();
    if (input.getSource() != null) {
      args.put("source", Map.of("set", StreamShadow.sourceArg(input.getSource())));
    }
    if (input.getProfile() != null) {
      args.put("profile", Map.of("set", input.getProfile()));
    }
    if (input.getBitrate() != null) {
      args.put(
          "bitrate",
          input.getBitrate() instanceof BitrateSetting.OverrideKbpsMember(int kbps)
              ? Map.of("set", kbps)
              : Map.of("configured", Map.of()));
    }
    if (input.getSourceCeilingPercent() != null) {
      args.put("source_ceiling_percent", Map.of("set", input.getSourceCeilingPercent()));
    }
    if (input.isRecord() != null) {
      args.put("record", Map.of("set", input.isRecord()));
    }
    return args;
  }

  private DeviceSummary device(String deviceId) {
    return devices
        .get(deviceId)
        .orElseThrow(() -> DeviceNotFound.builder().message("No device " + deviceId + ".").build());
  }

  /** The device's stream shadow, which must have its reported streams. */
  private StreamShadow reportedShadow(String deviceId) {
    StreamShadow shadow =
        new StreamShadow(
            shadows
                .get(device(deviceId).getThingName(), STREAM_SHADOW)
                .map(ShadowJson::parse)
                .orElse(null));
    if (!shadow.reported()) {
      throw unavailable("The device hasn't reported its streams (it may never have connected).");
    }
    return shadow;
  }

  /** The shadow of a device that reports stream {@code streamId}. */
  private StreamShadow existing(String deviceId, String streamId) {
    StreamShadow shadow =
        new StreamShadow(
            shadows
                .get(device(deviceId).getThingName(), STREAM_SHADOW)
                .map(ShadowJson::parse)
                .orElse(null));
    if (!shadow.hasStream(streamId)) {
      throw StreamNotFound.builder()
          .message("The device reports no stream " + streamId + ".")
          .build();
    }
    return shadow;
  }

  private static DeviceStateUnavailable unavailable(String message) {
    return DeviceStateUnavailable.builder().message(message).build();
  }
}
