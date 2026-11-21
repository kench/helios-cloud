package tv.kenley.helios.cloud.device;

import java.util.List;
import java.util.Optional;
import tv.kenley.helios.cloud.model.AwsSettings;
import tv.kenley.helios.cloud.model.TokenLabels;

/**
 * A device record from the {@code devices} table, as far as the device API needs it (Bootstrap API,
 * section 5). {@link #toString()} never includes the PIN.
 *
 * @param deviceId the service's ID, {@code d-} and a ULID
 * @param thingName the AWS IoT thing name
 * @param disabled whether the record is disabled
 * @param macs the device's built-in MACs
 * @param pin the token user PIN
 * @param token the token, key and challenge key labels
 * @param challengeRequired whether lookups must answer a challenge first
 * @param challengePublicKeyPem the registered challenge public key, if any
 * @param aws per-device AWS settings, if any
 */
record DeviceRecord(
    String deviceId,
    String thingName,
    boolean disabled,
    List<String> macs,
    String pin,
    TokenLabels token,
    boolean challengeRequired,
    Optional<String> challengePublicKeyPem,
    Optional<AwsSettings> aws) {

  DeviceRecord {
    macs = List.copyOf(macs);
  }

  @Override
  public String toString() {
    return "DeviceRecord[deviceId="
        + deviceId
        + ", thingName="
        + thingName
        + ", disabled="
        + disabled
        + ", macs="
        + macs
        + ", pin=*REDACTED*, challengeRequired="
        + challengeRequired
        + "]";
  }
}
