package tv.kenley.helios.cloud.device;

import java.time.Clock;
import java.util.function.Consumer;
import software.amazon.smithy.java.core.error.ModeledException;
import tv.kenley.helios.cloud.common.RestJson;
import tv.kenley.helios.cloud.model.AnswerChallengeInput;
import tv.kenley.helios.cloud.model.BootstrapInput;
import tv.kenley.helios.cloud.model.BootstrapResult;
import tv.kenley.helios.cloud.model.DeviceIdentity;

/**
 * The device API's HTTP side, in the {@code restJson1} protocol (Bootstrap API, section 2): routes
 * {@code POST /v1/device/bootstrap} and {@code POST /v1/device/bootstrap/challenge}, decodes and
 * validates the body, and turns results and modelled errors into responses. Every call writes one
 * audit log entry.
 */
final class DeviceApi {
  static final String BOOTSTRAP_PATH = "/v1/device/bootstrap";
  static final String CHALLENGE_PATH = "/v1/device/bootstrap/challenge";

  private final BootstrapService service;
  private final Consumer<String> auditLog;
  private final Consumer<String> errorLog;
  private final Clock clock;

  /** The API over {@code service}, writing audit entries and errors to the given logs. */
  DeviceApi(
      BootstrapService service, Consumer<String> auditLog, Consumer<String> errorLog, Clock clock) {
    this.service = service;
    this.auditLog = auditLog;
    this.errorLog = errorLog;
    this.clock = clock;
  }

  /** Handles one request. Never throws. */
  RestJson.Response handle(RestJson.Request request) {
    String operation =
        switch (request.method() + " " + request.path()) {
          case "POST " + BOOTSTRAP_PATH -> "Bootstrap";
          case "POST " + CHALLENGE_PATH -> "AnswerChallenge";
          default -> null;
        };
    if (operation == null) {
      return RestJson.unknownOperation();
    }
    AuditEntry audit =
        new AuditEntry(operation, clock.instant(), request.sourceIp(), request.requestId());
    RestJson.Response response;
    try {
      if (operation.equals("Bootstrap")) {
        BootstrapResult result =
            service.bootstrap(RestJson.decode(request.body(), BootstrapInput.builder()), audit);
        audit.result(result instanceof BootstrapResult.ChallengeMember ? "challenge" : "identity");
        // The output's only member is its @httpPayload: the union is the body.
        response = RestJson.ok(200, result);
      } else {
        DeviceIdentity identity =
            service.answerChallenge(
                RestJson.decode(request.body(), AnswerChallengeInput.builder()), audit);
        audit.result("identity");
        response = RestJson.ok(200, identity);
      }
    } catch (ModeledException e) {
      audit.result(RestJson.errorType(e));
      response = RestJson.error(e);
    } catch (RuntimeException e) {
      errorLog.accept(operation + " failed: " + e);
      audit.result("InternalError");
      response = RestJson.internalError();
    }
    auditLog.accept(audit.toJson());
    return response;
  }
}
