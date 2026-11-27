package tv.kenley.helios.cloud.control;

import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.java.core.serde.document.Document;

/**
 * What the Control API last set on each device, so operators can read it back (owner, 2026-11-26):
 * a stream's settings (its destination with any passphrase) and a Wi-Fi network's passphrase. Kept
 * in DynamoDB, encrypted at rest ({@link DynamoSettings}), or in memory in tests. Never logged.
 * Each item is a JSON object, keyed by device and {@code stream#<id>} or {@code wifi#<ssid>}.
 */
interface SettingsStore {
  /** The item, if there's one. */
  Optional<Document> get(String deviceId, String item);

  /** The device's items whose key starts with {@code prefix}, by the rest of the key. */
  Map<String, Document> all(String deviceId, String prefix);

  /** Writes the item, replacing what was there. */
  void put(String deviceId, String item, Map<String, Object> value);

  /** Removes the item; one that isn't there is fine. */
  void delete(String deviceId, String item);

  static String stream(String streamId) {
    return "stream#" + streamId;
  }

  static String wifi(String ssid) {
    return "wifi#" + ssid;
  }
}
