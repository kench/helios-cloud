package tv.kenley.helios.cloud.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.BitrateSetting;
import tv.kenley.helios.cloud.control.model.DeviceNotFound;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.GetDeviceOutput;
import tv.kenley.helios.cloud.control.model.GetStreamOutput;
import tv.kenley.helios.cloud.control.model.ListDevicesOutput;
import tv.kenley.helios.cloud.control.model.ListInputsOutput;
import tv.kenley.helios.cloud.control.model.ListProfilesOutput;
import tv.kenley.helios.cloud.control.model.ListStreamsOutput;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationInput;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationOutput;
import tv.kenley.helios.cloud.control.model.SettingUnavailable;
import tv.kenley.helios.cloud.control.model.StartStreamOutput;
import tv.kenley.helios.cloud.control.model.StopStreamOutput;
import tv.kenley.helios.cloud.control.model.StreamDescription;
import tv.kenley.helios.cloud.control.model.StreamNotFound;
import tv.kenley.helios.cloud.control.model.UpdateStreamInput;
import tv.kenley.helios.cloud.control.model.UpdateStreamOutput;

/**
 * The Control API's operations (HLD section 7.3): devices from the devices table, everything else
 * from the device's shadows. Changes are written as the {@code stream} shadow's desired state; the
 * device applies them and reports the result.
 */
final class ControlService {
  static final String STREAM_SHADOW = "stream";
  static final String HELIOS_SHADOW = "helios";
  static final int DEFAULT_PAGE = 50;

  private final DeviceDirectory devices;
  private final Shadows shadows;

  ControlService(DeviceDirectory devices, Shadows shadows) {
    this.devices = devices;
    this.shadows = shadows;
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

  ListStreamsOutput listStreams(String deviceId) {
    StreamShadow shadow = reportedShadow(deviceId);
    return ListStreamsOutput.builder()
        .streams(shadow.streamIds().stream().map(shadow::stream).toList())
        .build();
  }

  GetStreamOutput getStream(String deviceId, String streamId) {
    return GetStreamOutput.builder().stream(existing(deviceId, streamId).stream(streamId)).build();
  }

  StartStreamOutput start(String deviceId, String streamId, String clientToken) {
    Change change = change(deviceId, streamId, Map.of("state", "live"), clientToken);
    return StartStreamOutput.builder().stream(change.stream())
        .shadowVersion(change.version())
        .build();
  }

  StopStreamOutput stop(String deviceId, String streamId, String clientToken) {
    Change change = change(deviceId, streamId, Map.of("state", "stopped"), clientToken);
    return StopStreamOutput.builder().stream(change.stream())
        .shadowVersion(change.version())
        .build();
  }

  /**
   * Source, profile, bitrate and ceiling, each only if given.
   *
   * @throws SettingUnavailable if the device reports its inputs or profiles and doesn't have the
   *     source or profile asked for
   */
  UpdateStreamOutput update(UpdateStreamInput input) {
    StreamShadow shadow = existing(input.getDeviceId(), input.getStreamId());
    Map<String, Object> desired = new LinkedHashMap<>();
    if (input.getSource() != null) {
      if (!shadow.offers(input.getSource())) {
        throw SettingUnavailable.builder()
            .setting("source")
            .message("The device doesn't report this source among its inputs.")
            .build();
      }
      desired.put("source", StreamShadow.sourceFields(input.getSource()));
    }
    if (input.getProfile() != null) {
      if (!shadow.hasProfile(input.getProfile())) {
        throw SettingUnavailable.builder()
            .setting("profile")
            .message("The device has no profile " + input.getProfile() + ".")
            .build();
      }
      desired.put("profile", input.getProfile());
    }
    if (input.getBitrate() != null) {
      desired.put(
          "bitrate_kbps",
          input.getBitrate() instanceof BitrateSetting.OverrideKbpsMember(int kbps)
              ? (Object) kbps
              : ShadowJson.NULL);
    }
    if (input.getSourceCeilingPercent() != null) {
      desired.put("source_ceiling_percent", input.getSourceCeilingPercent());
    }
    Change change =
        write(input.getDeviceId(), shadow, input.getStreamId(), desired, input.getClientToken());
    return UpdateStreamOutput.builder().stream(change.stream())
        .shadowVersion(change.version())
        .build();
  }

  /**
   * Checks the URL and writes it in its normal form.
   *
   * @throws tv.kenley.helios.cloud.control.model.InvalidDestination if it isn't an SRT or SRTLA URL
   *     within the contract's rules
   */
  SetStreamDestinationOutput setDestination(SetStreamDestinationInput input) {
    String url = Destinations.parse(input.getUrl()).url();
    StreamShadow shadow = existing(input.getDeviceId(), input.getStreamId());
    Optional<String> previous = shadow.destination(input.getStreamId());
    Change change =
        write(
            input.getDeviceId(),
            shadow,
            input.getStreamId(),
            Map.of("destination", url),
            input.getClientToken());
    SetStreamDestinationOutput.Builder output =
        SetStreamDestinationOutput.builder().stream(change.stream())
            .shadowVersion(change.version());
    previous.ifPresent(output::previous);
    return output.build();
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private record Change(StreamDescription stream, long version) {}

  private Change change(
      String deviceId, String streamId, Map<String, Object> desired, String clientToken) {
    return write(deviceId, existing(deviceId, streamId), streamId, desired, clientToken);
  }

  private Change write(
      String deviceId,
      StreamShadow shadow,
      String streamId,
      Map<String, Object> desired,
      String clientToken) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put(
        "state",
        Map.of("desired", Map.of("streams", Map.of(streamId, new LinkedHashMap<>(desired)))));
    if (clientToken != null) {
      document.put("clientToken", clientToken);
    }
    long version =
        shadows.update(device(deviceId).getThingName(), STREAM_SHADOW, ShadowJson.bytes(document));
    return new Change(shadow.streamWith(streamId, desired), version);
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
