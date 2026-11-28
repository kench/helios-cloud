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
 * Management: Tenets and Practices"), the only way the Control API changes a stream. The device
 * applies a change whole or refuses it whole and answers within seconds, so the response shows the
 * stream as it is after the change. What was set is kept in the settings store, which GetStream
 * shows as {@code desired}. A Helios before 0.16.0 takes start and stop but not update, which it
 * answers as an unknown command.
 */
final class StreamCommands {
  static final String START = "helios-stream-start-v1";
  static final String STOP = "helios-stream-stop-v1";
  static final String UPDATE = "helios-stream-update-v1";

  private final CommandRunner runner;
  private final SettingsStore settings;

  StreamCommands(CommandRunner runner, SettingsStore settings) {
    this.runner = runner;
    this.settings = settings;
  }

  /** A change's result: the stream after it, and the {@code stream} shadow's version it read. */
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
    return new Applied(shadow.commanded(streamId, record, Optional.of(status)), shadow.version());
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
    if ("UNKNOWN_COMMAND".equals(code)) {
      throw DeviceStateUnavailable.builder()
          .message(
              "The device's Helios doesn't have this stream command (stream changes need Helios"
                  + " 0.16.0 or later): "
                  + description)
          .build();
    }
    if ("MODULE_UNAVAILABLE".equals(code)) {
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
