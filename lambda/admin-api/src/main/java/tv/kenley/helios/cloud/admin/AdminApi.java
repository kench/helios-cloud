package tv.kenley.helios.cloud.admin;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.smithy.java.core.error.ModeledException;
import tv.kenley.helios.cloud.common.Json;
import tv.kenley.helios.cloud.common.RestJson;
import tv.kenley.helios.cloud.model.DeleteDeviceInput;
import tv.kenley.helios.cloud.model.DeviceDescription;
import tv.kenley.helios.cloud.model.GetDeviceInput;
import tv.kenley.helios.cloud.model.GetDeviceOutput;
import tv.kenley.helios.cloud.model.PutChallengeKeyInput;
import tv.kenley.helios.cloud.model.PutChallengeKeyOutput;
import tv.kenley.helios.cloud.model.RegisterDeviceInput;
import tv.kenley.helios.cloud.model.RegisterDeviceOutput;
import tv.kenley.helios.cloud.model.SetDevicePinInput;
import tv.kenley.helios.cloud.model.SetDeviceStatusInput;
import tv.kenley.helios.cloud.model.SetDeviceStatusOutput;

/**
 * The admin API's HTTP side, in {@code restJson1} (Bootstrap API, section 4). API Gateway checks
 * the IAM signature before the request gets here; every call writes one audit line with the
 * caller's ARN. The PIN is never written to a log or a response.
 */
final class AdminApi {
  static final String DEVICES_PATH = "/v1/admin/devices";

  private static final Pattern DEVICE = Pattern.compile("^/v1/admin/devices/([^/]+)$");
  private static final Pattern STATUS = Pattern.compile("^/v1/admin/devices/([^/]+)/status$");
  private static final Pattern PIN = Pattern.compile("^/v1/admin/devices/([^/]+)/pin$");
  private static final Pattern CHALLENGE_KEY =
      Pattern.compile("^/v1/admin/devices/([^/]+)/challenge-key$");

  private final AdminService service;
  private final Consumer<String> auditLog;
  private final Consumer<String> errorLog;
  private final Clock clock;

  AdminApi(
      AdminService service, Consumer<String> auditLog, Consumer<String> errorLog, Clock clock) {
    this.service = service;
    this.auditLog = auditLog;
    this.errorLog = errorLog;
    this.clock = clock;
  }

  /** Handles one request. Never throws. */
  RestJson.Response handle(RestJson.Request request) {
    Optional<Route> route = route(request.method(), request.path());
    if (route.isEmpty()) {
      return RestJson.unknownOperation();
    }
    String operation = route.get().operation();
    String deviceId = route.get().deviceId();
    String result;
    RestJson.Response response;
    try {
      response =
          switch (operation) {
            case "RegisterDevice" -> {
              DeviceDescription device =
                  service.register(RestJson.decode(request.body(), RegisterDeviceInput.builder()));
              deviceId = device.getDeviceId();
              yield RestJson.ok(201, RegisterDeviceOutput.builder().device(device).build());
            }
            case "GetDevice" -> {
              String id = deviceId;
              yield RestJson.ok(
                  200,
                  GetDeviceOutput.builder()
                      .device(
                          service.get(
                              RestJson.decode(
                                      new byte[0], GetDeviceInput.builder(), b -> b.deviceId(id))
                                  .getDeviceId()))
                      .build());
            }
            case "SetDeviceStatus" -> {
              String id = deviceId;
              SetDeviceStatusInput input =
                  RestJson.decode(
                      request.body(), SetDeviceStatusInput.builder(), b -> b.deviceId(id));
              yield RestJson.ok(
                  200, SetDeviceStatusOutput.builder().device(service.setStatus(input)).build());
            }
            case "SetDevicePin" -> {
              String id = deviceId;
              service.setPin(
                  RestJson.decode(
                      request.body(), SetDevicePinInput.builder(), b -> b.deviceId(id)));
              yield RestJson.noContent();
            }
            case "PutChallengeKey" -> {
              String id = deviceId;
              PutChallengeKeyInput input =
                  RestJson.decode(
                      request.body(), PutChallengeKeyInput.builder(), b -> b.deviceId(id));
              yield RestJson.ok(
                  200,
                  PutChallengeKeyOutput.builder()
                      .challenge(service.putChallengeKey(input))
                      .build());
            }
            default -> {
              String id = deviceId;
              service.delete(
                  RestJson.decode(new byte[0], DeleteDeviceInput.builder(), b -> b.deviceId(id))
                      .getDeviceId());
              yield RestJson.noContent();
            }
          };
      result = "ok";
    } catch (ModeledException e) {
      result = RestJson.errorType(e);
      response = RestJson.error(e);
    } catch (RuntimeException e) {
      errorLog.accept(operation + " failed: " + e);
      result = "InternalError";
      response = RestJson.internalError();
    }
    auditLog.accept(audit(request, operation, deviceId, result));
    return response;
  }

  private record Route(String operation, String deviceId) {}

  private static Optional<Route> route(String method, String path) {
    if (method.equals("POST") && path.equals(DEVICES_PATH)) {
      return Optional.of(new Route("RegisterDevice", null));
    }
    Matcher device = DEVICE.matcher(path);
    if (device.matches()) {
      return switch (method) {
        case "GET" -> Optional.of(new Route("GetDevice", device.group(1)));
        case "DELETE" -> Optional.of(new Route("DeleteDevice", device.group(1)));
        default -> Optional.empty();
      };
    }
    if (method.equals("PUT")) {
      for (Map.Entry<Pattern, String> entry :
          Map.of(
                  STATUS, "SetDeviceStatus",
                  PIN, "SetDevicePin",
                  CHALLENGE_KEY, "PutChallengeKey")
              .entrySet()) {
        Matcher matcher = entry.getKey().matcher(path);
        if (matcher.matches()) {
          return Optional.of(new Route(entry.getValue(), matcher.group(1)));
        }
      }
    }
    return Optional.empty();
  }

  private String audit(RestJson.Request request, String operation, String deviceId, String result) {
    List<String> fields = new ArrayList<>();
    fields.add(Json.field("type", "admin-audit"));
    fields.add(Json.field("time", clock.instant().toString()));
    fields.add(Json.field("requestId", request.requestId()));
    fields.add(Json.field("operation", operation));
    fields.add(Json.field("caller", request.caller() == null ? "unknown" : request.caller()));
    fields.add(Json.field("sourceIp", request.sourceIp()));
    if (deviceId != null) {
      fields.add(Json.field("deviceId", deviceId));
    }
    fields.add(Json.field("result", result));
    return "{" + String.join(",", fields) + "}";
  }
}
