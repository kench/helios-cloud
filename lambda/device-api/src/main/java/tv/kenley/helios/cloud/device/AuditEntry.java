package tv.kenley.helios.cloud.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One device API call, for the audit log (Bootstrap API, section 5): time, source IP, MACs, the
 * matched device, the result and whether a challenge was involved. Never the PIN, nonce or
 * signature. Filled in as the call proceeds, then written once.
 */
final class AuditEntry {
  private final String operation;
  private final Instant time;
  private final String sourceIp;
  private final String requestId;
  private List<String> macs = List.of();
  private List<String> unregisteredMacs = List.of();
  private Optional<String> deviceId = Optional.empty();
  private boolean challenge;
  private String result = "InternalError";

  AuditEntry(String operation, Instant time, String sourceIp, String requestId) {
    this.operation = operation;
    this.time = time;
    this.sourceIp = sourceIp;
    this.requestId = requestId;
  }

  void macs(List<String> macs) {
    this.macs = List.copyOf(macs);
  }

  void unregisteredMacs(List<String> macs) {
    this.unregisteredMacs = List.copyOf(macs);
  }

  void deviceId(String deviceId) {
    this.deviceId = Optional.of(deviceId);
  }

  void challenge() {
    this.challenge = true;
  }

  void result(String result) {
    this.result = result;
  }

  String sourceIp() {
    return sourceIp;
  }

  Instant time() {
    return time;
  }

  /** The entry as one line of JSON. */
  String toJson() {
    List<String> fields = new ArrayList<>();
    fields.add(Json.field("type", "audit"));
    fields.add(Json.field("time", time.toString()));
    fields.add(Json.field("requestId", requestId));
    fields.add(Json.field("operation", operation));
    fields.add(Json.field("sourceIp", sourceIp));
    fields.add(Json.quote("macs") + ":" + Json.array(macs));
    if (!unregisteredMacs.isEmpty()) {
      fields.add(Json.quote("unregisteredMacs") + ":" + Json.array(unregisteredMacs));
    }
    deviceId.ifPresent(id -> fields.add(Json.field("deviceId", id)));
    fields.add(Json.quote("challenge") + ":" + challenge);
    fields.add(Json.field("result", result));
    return "{" + String.join(",", fields) + "}";
  }
}
