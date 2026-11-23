package tv.kenley.helios.cloud.control;

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
import tv.kenley.helios.cloud.control.model.DeleteWifiNetworkInput;
import tv.kenley.helios.cloud.control.model.GetDeviceInput;
import tv.kenley.helios.cloud.control.model.GetDeviceLocationInput;
import tv.kenley.helios.cloud.control.model.GetStreamInput;
import tv.kenley.helios.cloud.control.model.ListDevicesInput;
import tv.kenley.helios.cloud.control.model.ListInputsInput;
import tv.kenley.helios.cloud.control.model.ListProfilesInput;
import tv.kenley.helios.cloud.control.model.ListStreamsInput;
import tv.kenley.helios.cloud.control.model.ListWifiNetworksInput;
import tv.kenley.helios.cloud.control.model.SetStreamDestinationInput;
import tv.kenley.helios.cloud.control.model.SetWifiNetworkInput;
import tv.kenley.helios.cloud.control.model.StartStreamInput;
import tv.kenley.helios.cloud.control.model.StopStreamInput;
import tv.kenley.helios.cloud.control.model.UpdateStreamInput;

/**
 * The Control API's HTTP side, in {@code restJson1} (HLD section 7.3). API Gateway checks the IAM
 * signature before the request gets here; every call writes one audit line with the caller's ARN,
 * and a destination change also records the new destination (its SRT passphrase as {@code ***}), a
 * Wi-Fi change the SSID (never the passphrase).
 */
final class ControlApi {
  private static final String DEVICES = "^/v1/control/devices";
  private static final String DEVICE = DEVICES + "/([^/]+)";
  private static final String STREAM = DEVICE + "/streams/([^/]+)";

  private static final Pattern LIST_DEVICES = Pattern.compile(DEVICES + "$");
  private static final Pattern GET_DEVICE = Pattern.compile(DEVICE + "$");
  private static final Pattern INPUTS = Pattern.compile(DEVICE + "/inputs$");
  private static final Pattern PROFILES = Pattern.compile(DEVICE + "/profiles$");
  private static final Pattern LOCATION = Pattern.compile(DEVICE + "/location$");
  private static final Pattern WIFI = Pattern.compile(DEVICE + "/wifi/networks$");
  private static final Pattern STREAMS = Pattern.compile(DEVICE + "/streams$");
  private static final Pattern ONE_STREAM = Pattern.compile(STREAM + "$");
  private static final Pattern START = Pattern.compile(STREAM + "/start$");
  private static final Pattern STOP = Pattern.compile(STREAM + "/stop$");
  private static final Pattern DESTINATION = Pattern.compile(STREAM + "/destination$");

  private final ControlService service;
  private final WifiControl wifi;
  private final Consumer<String> auditLog;
  private final Consumer<String> errorLog;
  private final Clock clock;

  ControlApi(
      ControlService service,
      WifiControl wifi,
      Consumer<String> auditLog,
      Consumer<String> errorLog,
      Clock clock) {
    this.service = service;
    this.wifi = wifi;
    this.auditLog = auditLog;
    this.errorLog = errorLog;
    this.clock = clock;
  }

  /** Handles one request, with its query parameters. Never throws. */
  RestJson.Response handle(RestJson.Request request, Map<String, String> query) {
    Optional<Route> found = route(request.method(), request.path());
    if (found.isEmpty()) {
      return RestJson.unknownOperation();
    }
    Route route = found.get();
    String result;
    String destination = null;
    String ssid = null;
    RestJson.Response response;
    try {
      byte[] body = request.body();
      String device = route.deviceId();
      String stream = route.streamId();
      response =
          switch (route.operation()) {
            case "ListDevices" -> {
              ListDevicesInput input =
                  RestJson.decode(
                      new byte[0],
                      ListDevicesInput.builder(),
                      b -> {
                        b.nextToken(query.get("nextToken"));
                        b.maxResults(integer(query.get("maxResults")));
                      });
              yield RestJson.ok(
                  200, service.listDevices(input.getNextToken(), input.getMaxResults()));
            }
            case "GetDevice" -> {
              GetDeviceInput input =
                  RestJson.decode(new byte[0], GetDeviceInput.builder(), b -> b.deviceId(device));
              yield RestJson.ok(200, service.getDevice(input.getDeviceId()));
            }
            case "ListInputs" -> {
              ListInputsInput input =
                  RestJson.decode(new byte[0], ListInputsInput.builder(), b -> b.deviceId(device));
              yield RestJson.ok(200, service.listInputs(input.getDeviceId()));
            }
            case "ListProfiles" -> {
              ListProfilesInput input =
                  RestJson.decode(
                      new byte[0], ListProfilesInput.builder(), b -> b.deviceId(device));
              yield RestJson.ok(200, service.listProfiles(input.getDeviceId()));
            }
            case "GetDeviceLocation" -> {
              GetDeviceLocationInput input =
                  RestJson.decode(
                      new byte[0], GetDeviceLocationInput.builder(), b -> b.deviceId(device));
              yield RestJson.ok(200, service.getDeviceLocation(input.getDeviceId()));
            }
            case "ListWifiNetworks" -> {
              ListWifiNetworksInput input =
                  RestJson.decode(
                      new byte[0], ListWifiNetworksInput.builder(), b -> b.deviceId(device));
              yield RestJson.ok(200, wifi.list(input.getDeviceId()));
            }
            case "SetWifiNetwork" -> {
              SetWifiNetworkInput input =
                  RestJson.decode(body, SetWifiNetworkInput.builder(), b -> b.deviceId(device));
              ssid = input.getSsid();
              yield RestJson.ok(200, wifi.set(input));
            }
            case "DeleteWifiNetwork" -> {
              DeleteWifiNetworkInput input =
                  RestJson.decode(
                      new byte[0],
                      DeleteWifiNetworkInput.builder(),
                      b -> b.deviceId(device).ssid(query.get("ssid")));
              ssid = input.getSsid();
              yield RestJson.ok(200, wifi.delete(input.getDeviceId(), input.getSsid()));
            }
            case "ListStreams" -> {
              ListStreamsInput input =
                  RestJson.decode(new byte[0], ListStreamsInput.builder(), b -> b.deviceId(device));
              yield RestJson.ok(200, service.listStreams(input.getDeviceId()));
            }
            case "GetStream" -> {
              GetStreamInput input =
                  RestJson.decode(
                      new byte[0],
                      GetStreamInput.builder(),
                      b -> b.deviceId(device).streamId(stream));
              yield RestJson.ok(200, service.getStream(input.getDeviceId(), input.getStreamId()));
            }
            case "StartStream" -> {
              StartStreamInput input =
                  RestJson.decode(
                      body, StartStreamInput.builder(), b -> b.deviceId(device).streamId(stream));
              yield RestJson.ok(
                  202,
                  service.start(input.getDeviceId(), input.getStreamId(), input.getClientToken()));
            }
            case "StopStream" -> {
              StopStreamInput input =
                  RestJson.decode(
                      body, StopStreamInput.builder(), b -> b.deviceId(device).streamId(stream));
              yield RestJson.ok(
                  202,
                  service.stop(input.getDeviceId(), input.getStreamId(), input.getClientToken()));
            }
            case "UpdateStream" -> {
              UpdateStreamInput input =
                  RestJson.decode(
                      body, UpdateStreamInput.builder(), b -> b.deviceId(device).streamId(stream));
              yield RestJson.ok(202, service.update(input));
            }
            default -> {
              SetStreamDestinationInput input =
                  RestJson.decode(
                      body,
                      SetStreamDestinationInput.builder(),
                      b -> b.deviceId(device).streamId(stream));
              destination = Destinations.redact(input.getUrl());
              yield RestJson.ok(202, service.setDestination(input));
            }
          };
      result = "ok";
    } catch (ModeledException e) {
      result = RestJson.errorType(e);
      response = RestJson.error(e);
    } catch (RuntimeException e) {
      errorLog.accept(route.operation() + " failed: " + e);
      result = "InternalError";
      response = RestJson.internalError();
    }
    auditLog.accept(audit(request, route, destination, ssid, result));
    return response;
  }

  /** {@code text} as an int, or {@code null}; a non-number becomes a validation error. */
  private static Integer integer(String text) {
    if (text == null) {
      return null;
    }
    try {
      return Integer.valueOf(text);
    } catch (NumberFormatException e) {
      throw software.amazon.smithy.java.framework.model.ValidationException.builder()
          .message("maxResults must be a number.")
          .build();
    }
  }

  record Route(String operation, String deviceId, String streamId) {}

  static Optional<Route> route(String method, String path) {
    record Rule(String method, Pattern pattern, String operation) {}

    List<Rule> rules =
        List.of(
            new Rule("GET", LIST_DEVICES, "ListDevices"),
            new Rule("GET", GET_DEVICE, "GetDevice"),
            new Rule("GET", INPUTS, "ListInputs"),
            new Rule("GET", PROFILES, "ListProfiles"),
            new Rule("GET", LOCATION, "GetDeviceLocation"),
            new Rule("GET", WIFI, "ListWifiNetworks"),
            new Rule("PUT", WIFI, "SetWifiNetwork"),
            new Rule("DELETE", WIFI, "DeleteWifiNetwork"),
            new Rule("GET", STREAMS, "ListStreams"),
            new Rule("GET", ONE_STREAM, "GetStream"),
            new Rule("PATCH", ONE_STREAM, "UpdateStream"),
            new Rule("POST", START, "StartStream"),
            new Rule("POST", STOP, "StopStream"),
            new Rule("PUT", DESTINATION, "SetStreamDestination"));
    for (Rule rule : rules) {
      Matcher matcher = rule.pattern().matcher(path);
      if (rule.method().equals(method) && matcher.matches()) {
        String deviceId = matcher.groupCount() >= 1 ? matcher.group(1) : null;
        String streamId = matcher.groupCount() >= 2 ? matcher.group(2) : null;
        return Optional.of(new Route(rule.operation(), deviceId, streamId));
      }
    }
    return Optional.empty();
  }

  private String audit(
      RestJson.Request request, Route route, String destination, String ssid, String result) {
    List<String> fields = new ArrayList<>();
    fields.add(Json.field("type", "control-audit"));
    fields.add(Json.field("time", clock.instant().toString()));
    fields.add(Json.field("requestId", request.requestId()));
    fields.add(Json.field("operation", route.operation()));
    fields.add(Json.field("caller", request.caller() == null ? "unknown" : request.caller()));
    fields.add(Json.field("sourceIp", request.sourceIp()));
    if (route.deviceId() != null) {
      fields.add(Json.field("deviceId", route.deviceId()));
    }
    if (route.streamId() != null) {
      fields.add(Json.field("streamId", route.streamId()));
    }
    if (destination != null) {
      fields.add(Json.field("destination", destination));
    }
    if (ssid != null) {
      fields.add(Json.field("ssid", ssid));
    }
    fields.add(Json.field("result", result));
    return "{" + String.join(",", fields) + "}";
  }
}
