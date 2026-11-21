package tv.kenley.helios.cloud.common;

import java.nio.charset.StandardCharsets;
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
import tv.kenley.helios.cloud.model.InternalError;

/**
 * The {@code restJson1} protocol as the Lambdas use it (Bootstrap API, section 2): JSON bodies in
 * the generated types, validated against the model's constraints, and modelled errors as their HTTP
 * status, an {@code X-Amzn-Errortype} header and a {@code message}. Every response has {@code
 * Cache-Control: no-store}.
 */
public final class RestJson {
  /** The body codec: member names as modelled, timestamps per their traits. */
  private static final JsonCodec CODEC =
      JsonCodec.builder().useJsonName(true).useTimestampFormat(true).build();

  private static final Validator VALIDATOR = Validator.builder().build();

  private RestJson() {}

  /**
   * An HTTP request, as far as the Lambdas need it.
   *
   * @param method the HTTP method
   * @param path the resource path, without the stage
   * @param body the body bytes, empty if there was none
   * @param sourceIp the caller's address
   * @param requestId the Lambda request ID
   * @param caller the IAM principal that signed the request, or {@code null} for unsigned routes
   */
  public record Request(
      String method, String path, byte[] body, String sourceIp, String requestId, String caller) {}

  /**
   * An HTTP response with a JSON body (or none, for 204).
   *
   * @param status the HTTP status
   * @param headers the response headers
   * @param body the body
   */
  public record Response(int status, Map<String, String> headers, String body) {}

  /**
   * Decodes {@code body} into {@code builder}, applies {@code labels} (members bound to the URL
   * path), builds the shape and checks it against the model's constraints.
   *
   * @throws ValidationException if the body isn't valid JSON for the shape, or a constraint fails
   */
  public static <T extends SerializableShape, B extends ShapeBuilder<T>> T decode(
      byte[] body, B builder, Consumer<? super B> labels) {
    byte[] json = body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body;
    T shape;
    try {
      builder.deserialize(CODEC.createDeserializer(json));
      labels.accept(builder);
      shape = builder.build();
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

  /** Decodes a body with no members bound to the path. */
  public static <T extends SerializableShape, B extends ShapeBuilder<T>> T decode(
      byte[] body, B builder) {
    return decode(body, builder, b -> {});
  }

  /** {@code status} with {@code body} serialized as JSON. */
  public static Response ok(int status, SerializableStruct body) {
    return new Response(status, headers(Map.of()), CODEC.serializeToString(body));
  }

  /** 204 No Content. */
  public static Response noContent() {
    return new Response(204, headers(Map.of()), "");
  }

  /** A modelled error: its {@code @httpError} status, error type header and JSON members. */
  public static Response error(ModeledException e) {
    return new Response(
        ModeledException.getHttpStatusCode(e.schema()),
        headers(Map.of("X-Amzn-Errortype", errorType(e))),
        CODEC.serializeToString(e));
  }

  /** 500 InternalError with a generic message; the detail belongs in the log, not the body. */
  public static Response internalError() {
    return error(InternalError.builder().message("Internal error.").build());
  }

  /** 404 for a method and path that aren't an operation. */
  public static Response unknownOperation() {
    return new Response(
        404,
        headers(Map.of("X-Amzn-Errortype", "UnknownOperationException")),
        "{\"message\":\"Not found.\"}");
  }

  /** The error's shape name, as in {@code X-Amzn-Errortype}. */
  public static String errorType(ModeledException e) {
    return e.schema().id().getName();
  }

  private static Map<String, String> headers(Map<String, String> extra) {
    Map<String, String> headers = new TreeMap<>(extra);
    headers.put("Content-Type", "application/json");
    headers.put("Cache-Control", "no-store");
    return Map.copyOf(headers);
  }
}
