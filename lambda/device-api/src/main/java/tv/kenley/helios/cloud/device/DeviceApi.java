package tv.kenley.helios.cloud.device;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.SerializableShape;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.core.schema.ShapeBuilder;
import software.amazon.smithy.java.core.schema.ValidationError;
import software.amazon.smithy.java.core.schema.Validator;
import software.amazon.smithy.java.framework.model.ValidationException;
import software.amazon.smithy.java.framework.model.ValidationExceptionField;
import software.amazon.smithy.java.json.JsonCodec;
import tv.kenley.helios.cloud.model.AnswerChallengeInput;
import tv.kenley.helios.cloud.model.BootstrapInput;
import tv.kenley.helios.cloud.model.BootstrapResult;
import tv.kenley.helios.cloud.model.DeviceIdentity;
import tv.kenley.helios.cloud.model.InternalError;

/**
 * The device API's HTTP side, in the {@code restJson1} protocol (Bootstrap API, section 2): routes
 * {@code POST /v1/device/bootstrap} and {@code POST /v1/device/bootstrap/challenge}, decodes and
 * validates the JSON body against the model, and turns results and modelled errors into responses.
 * Every response has {@code Cache-Control: no-store}, and every call writes one audit log entry.
 */
final class DeviceApi {
  static final String BOOTSTRAP_PATH = "/v1/device/bootstrap";
  static final String CHALLENGE_PATH = "/v1/device/bootstrap/challenge";

  /** The restJson1 body codec: member names as modelled, timestamps per their traits. */
  private static final JsonCodec CODEC =
      JsonCodec.builder().useJsonName(true).useTimestampFormat(true).build();

  private static final Validator VALIDATOR = Validator.builder().build();

  private final BootstrapService service;
  private final Consumer<String> auditLog;
  private final Consumer<String> errorLog;
  private final Clock clock;

  /** An HTTP request, as far as the API needs it. */
  record Request(String method, String path, byte[] body, String sourceIp, String requestId) {}

  /** An HTTP response with a JSON body. */
  record Response(int status, Map<String, String> headers, String body) {}

  /** The API over {@code service}, writing audit entries and errors to the given logs. */
  DeviceApi(
      BootstrapService service, Consumer<String> auditLog, Consumer<String> errorLog, Clock clock) {
    this.service = service;
    this.auditLog = auditLog;
    this.errorLog = errorLog;
    this.clock = clock;
  }

  /** Handles one request. Never throws. */
  Response handle(Request request) {
    String route = request.method() + " " + request.path();
    String operation =
        switch (route) {
          case "POST " + BOOTSTRAP_PATH -> "Bootstrap";
          case "POST " + CHALLENGE_PATH -> "AnswerChallenge";
          default -> null;
        };
    if (operation == null) {
      return error(404, "UnknownOperationException", "{\"message\":\"Not found.\"}");
    }
    AuditEntry audit =
        new AuditEntry(operation, clock.instant(), request.sourceIp(), request.requestId());
    Response response;
    try {
      response =
          switch (operation) {
            case "Bootstrap" -> {
              BootstrapResult result =
                  service.bootstrap(decode(request.body(), BootstrapInput.builder()), audit);
              audit.result(
                  result instanceof BootstrapResult.ChallengeMember ? "challenge" : "identity");
              yield ok(result);
            }
            default -> {
              DeviceIdentity identity =
                  service.answerChallenge(
                      decode(request.body(), AnswerChallengeInput.builder()), audit);
              audit.result("identity");
              yield ok(identity);
            }
          };
    } catch (ModeledException e) {
      audit.result(e.schema().id().getName());
      response = modelled(e);
    } catch (RuntimeException e) {
      errorLog.accept(operation + " failed: " + e);
      InternalError internal = InternalError.builder().message("Internal error.").build();
      audit.result("InternalError");
      response = modelled(internal);
    }
    auditLog.accept(audit.toJson());
    return response;
  }

  /** Decodes {@code body} into a shape and checks it against the model's constraints. */
  private static <T extends SerializableShape> T decode(byte[] body, ShapeBuilder<T> builder) {
    byte[] json = body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body;
    T shape;
    try {
      shape = CODEC.deserializeShape(json, builder);
    } catch (RuntimeException e) {
      throw ValidationException.builder()
          .message("The request body isn't valid JSON for this operation.")
          .build();
    }
    List<ValidationError> errors = VALIDATOR.validate(shape);
    if (!errors.isEmpty()) {
      throw ValidationException.builder()
          .message(errors.size() + " validation error(s): " + errors.getFirst().message())
          .fieldList(
              errors.stream()
                  .map(error ->
                      ValidationExceptionField.builder()
                          .path(error.path())
                          .message(error.message())
                          .build())
                  .toList())
          .build();
    }
    return shape;
  }

  private static Response ok(SerializableStruct payload) {
    return new Response(200, headers(Map.of()), CODEC.serializeToString(payload));
  }

  private static Response modelled(ModeledException e) {
    return error(
        ModeledException.getHttpStatusCode(e.schema()),
        e.schema().id().getName(),
        CODEC.serializeToString(e));
  }

  private static Response error(int status, String type, String body) {
    return new Response(status, headers(Map.of("X-Amzn-Errortype", type)), body);
  }

  private static Map<String, String> headers(Map<String, String> extra) {
    Map<String, String> headers = new TreeMap<>(extra);
    headers.put("Content-Type", "application/json");
    headers.put("Cache-Control", "no-store");
    return Map.copyOf(headers);
  }
}
