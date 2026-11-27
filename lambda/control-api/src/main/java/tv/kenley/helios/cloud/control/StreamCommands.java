package tv.kenley.helios.cloud.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.DeviceStateUnavailable;
import tv.kenley.helios.cloud.control.model.DeviceSummary;
import tv.kenley.helios.cloud.control.model.RefusedSetting;
import tv.kenley.helios.cloud.control.model.StreamChangeRefused;
import tv.kenley.helios.cloud.control.model.StreamDescription;
import tv.kenley.helios.cloud.control.model.StreamNotFound;

/**
 * Stream changes as commands (helios-contract {@code device.smithy}; the page "Helios - State
 * Management: Tenets and Practices"), for devices whose Helios has them (0.16.0 and later). The
 * device applies a change whole or refuses it whole and answers within seconds, so the response
 * shows the stream as it is after the change. What was set is kept in the settings store, which
 * GetStream shows as {@code desired}; the stream's old desired state is cleared, so it can't undo a
 * command when the device reconnects.
 */
final class StreamCommands {
  static final String START = "helios-stream-start-v1";
  static final String STOP = "helios-stream-stop-v1";
  static final String UPDATE = "helios-stream-update-v1";

  /** The first Helios with the stream commands. */
  private static final int[] FIRST = {0, 16, 0};

  private final CommandRunner runner;
  private final Shadows shadows;
  private final SettingsStore settings;

  StreamCommands(CommandRunner runner, Shadows shadows, SettingsStore settings) {
    this.runner = runner;
    this.shadows = shadows;
    this.settings = settings;
  }

  /** Whether {@code device} takes stream changes as commands: its Helios is 0.16.0 or later. */
  static boolean supports(DeviceSummary device) {
    String version = device.getHeliosVersion();
    if (version == null) {
      return false;
    }
    String[] parts = version.split("[.+-]");
    for (int i = 0; i < FIRST.length; i++) {
      int part;
      try {
        part = i < parts.length ? Integer.parseInt(parts[i]) : 0;
      } catch (NumberFormatException e) {
        return false;
      }
      if (part != FIRST[i]) {
        return part > FIRST[i];
      }
    }
    return true;
  }

  /** A change's result: the stream after it, and the {@code stream} shadow's version. */
  record Applied(StreamDescription stream, long version) {}

  /** Stream {@code streamId} with what this API last set as {@code desired}. */
  StreamDescription describe(DeviceSummary device, StreamShadow shadow, String streamId) {
    return shadow.commanded(streamId, recorded(device.getDeviceId(), streamId), Optional.empty());
  }

  /** What this API last set on the stream, in shadow form; empty if nothing. */
  Map<String, Object> recorded(String deviceId, String streamId) {
    return settings
        .get(deviceId, SettingsStore.stream(streamId))
        .map(StreamShadow::settingsOf)
        .orElseGet(LinkedHashMap::new);
  }

  /**
   * Runs {@code commandId} with {@code args} and, once the device has applied it, records {@code
   * changes} (shadow form) as what was set.
   *
   * @throws StreamChangeRefused if the device refused it (and changed nothing)
   * @throws tv.kenley.helios.cloud.control.model.DeviceUnreachable if it didn't answer in time
   */
  Applied apply(
      DeviceSummary device,
      StreamShadow shadow,
      String streamId,
      String commandId,
      Map<String, Object> args,
      Map<String, Object> changes) {
    Document result = outcome(runner.run(device.getThingName(), commandId, args), streamId);
    List<RefusedSetting> refused =
        ShadowJson.list(result, "refused").stream()
            .map(StreamCommands::refusedOf)
            .flatMap(Optional::stream)
            .toList();
    if (!refused.isEmpty()) {
      throw StreamChangeRefused.builder()
          .message("The device refused the change and changed nothing.")
          .refused(refused)
          .build();
    }
    // A Helios before 0.16.0 answered start and stop with the status itself.
    Document status = ShadowJson.at(result, "status").orElse(result);
    Map<String, Object> record = recorded(device.getDeviceId(), streamId);
    record.putAll(changes);
    settings.put(device.getDeviceId(), SettingsStore.stream(streamId), record);
    long version = clearDesired(device.getThingName(), shadow, streamId);
    return new Applied(shadow.commanded(streamId, record, Optional.of(status)), version);
  }

  /** Removes the stream's desired state, if it has any; the shadow's version after. */
  private long clearDesired(String thing, StreamShadow shadow, String streamId) {
    if (!shadow.hasDesired(streamId)) {
      return shadow.version();
    }
    Map<String, Object> streams = new LinkedHashMap<>();
    streams.put(streamId, ShadowJson.NULL);
    Map<String, Object> document = Map.of("state", Map.of("desired", Map.of("streams", streams)));
    return shadows.update(thing, ControlService.STREAM_SHADOW, ShadowJson.bytes(document));
  }

  /** A processed command's result, or the error for one the device couldn't process. */
  private static Document outcome(Commands.Execution execution, String streamId) {
    if ("SUCCEEDED".equals(execution.status())) {
      return CommandRunner.result(execution);
    }
    String code = execution.reasonCode() == null ? "INTERNAL" : execution.reasonCode();
    String description = execution.reasonDescription() == null ? "" : execution.reasonDescription();
    if ("INVALID_MESSAGE".equals(code) && description.startsWith("no stream")) {
      throw StreamNotFound.builder().message("The device has no stream " + streamId + ".").build();
    }
    if ("MODULE_UNAVAILABLE".equals(code) || "UNKNOWN_COMMAND".equals(code)) {
      throw DeviceStateUnavailable.builder()
          .message("The device couldn't take the change (" + code + "): " + description)
          .build();
    }
    throw new IllegalStateException("the device failed the command: " + code + " " + description);
  }

  /** A refusal, with the setting named as this API names it. */
  private static Optional<RefusedSetting> refusedOf(Document refusal) {
    Optional<String> setting = ShadowJson.string(refusal, "setting");
    Optional<String> reason = ShadowJson.string(refusal, "reason");
    if (setting.isEmpty() || reason.isEmpty()) {
      return Optional.empty();
    }
    String name =
        switch (setting.get()) {
          case "source_ceiling_percent" -> "sourceCeilingPercent";
          default -> setting.get();
        };
    return Optional.of(RefusedSetting.builder().setting(name).reason(reason.get()).build());
  }
}
