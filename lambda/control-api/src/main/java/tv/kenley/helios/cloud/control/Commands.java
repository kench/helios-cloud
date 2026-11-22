package tv.kenley.helios.cloud.control;

import java.util.Optional;

/**
 * Helios commands sent as AWS IoT command executions (LLD 2, section 7.3): {@link IotCommands}, or
 * a test's.
 */
interface Commands {
  /**
   * Starts command {@code commandId} on {@code thingName} with {@code args} (base64 of the
   * arguments' JSON) and an execution timeout, and returns the execution's id.
   */
  String start(String thingName, String commandId, String args, int timeoutSeconds);

  /** The execution, if AWS knows it. */
  Optional<Execution> get(String thingName, String executionId);

  /**
   * An execution as far as it got: AWS's status ({@code CREATED}, {@code IN_PROGRESS}, {@code
   * SUCCEEDED}, {@code FAILED}, {@code REJECTED}, {@code TIMED_OUT}), the device's reason code and
   * description, and its result's JSON (Helios sends one string entry, {@code json}).
   */
  record Execution(String status, String reasonCode, String reasonDescription, String result) {
    /** Whether the device (or AWS, for a timeout) has finished with it. */
    boolean finished() {
      return switch (status) {
        case "SUCCEEDED", "FAILED", "REJECTED" -> true;
        // AWS's own TIMED_OUT ($NO_RESPONSE_FROM_DEVICE) isn't terminal, but the caller has
        // waited as long as the execution allowed.
        case "TIMED_OUT" -> true;
        default -> false;
      };
    }
  }
}
