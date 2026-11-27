package tv.kenley.helios.cloud.control;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;
import tv.kenley.helios.cloud.control.model.DeviceUnreachable;

/**
 * Runs a Helios command on a device and waits for its answer (LLD 2, section 7.3; the page "Helios
 * - State Management: Tenets and Practices"): an AWS IoT command execution, polled until the device
 * answers or the execution's timeout passes. Commands are never queued, so a device that doesn't
 * answer in time is unreachable.
 */
final class CommandRunner {
  /** The execution's timeout: well inside API Gateway's 29 s and the Lambda's 15 s. */
  static final int TIMEOUT_SECONDS = 10;

  static final Duration POLL = Duration.ofMillis(400);

  /** How long after the timeout to keep looking, for an answer on its way. */
  static final Duration GRACE = Duration.ofSeconds(1);

  /** Waits between polls; tests don't. */
  interface Pause {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final Commands commands;
  private final Clock clock;
  private final Pause pause;

  CommandRunner(Commands commands, Clock clock, Pause pause) {
    this.commands = commands;
    this.clock = clock;
    this.pause = pause;
  }

  /**
   * Runs {@code commandId} on {@code thing} with {@code args} and returns the device's answer
   * (SUCCEEDED, FAILED or REJECTED).
   *
   * @throws DeviceUnreachable if it doesn't answer in time, or answers that it ran out of time
   */
  Commands.Execution run(String thing, String commandId, Map<String, Object> args) {
    String encoded = Base64.getEncoder().encodeToString(ShadowJson.bytes(args));
    String execution = commands.start(thing, commandId, encoded, TIMEOUT_SECONDS);
    Instant until = clock.instant().plusSeconds(TIMEOUT_SECONDS).plus(GRACE);
    while (true) {
      Optional<Commands.Execution> state = commands.get(thing, execution);
      if (state.isPresent() && state.get().finished()) {
        Commands.Execution done = state.get();
        if ("TIMED_OUT".equals(done.status()) || "TIMEOUT".equals(done.reasonCode())) {
          throw unreachable();
        }
        return done;
      }
      if (!clock.instant().isBefore(until)) {
        throw unreachable();
      }
      try {
        pause.sleep(POLL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw unreachable();
      }
    }
  }

  /** A SUCCEEDED execution's result, {@code {}} if it has none. */
  static Document result(Commands.Execution execution) {
    return ShadowJson.parse(
        (execution.result() == null ? "{}" : execution.result()).getBytes(StandardCharsets.UTF_8));
  }

  static DeviceUnreachable unreachable() {
    return DeviceUnreachable.builder()
        .message(
            "The device didn't answer in "
                + TIMEOUT_SECONDS
                + " s: it may be offline. Nothing was"
                + " changed.")
        .build();
  }
}
