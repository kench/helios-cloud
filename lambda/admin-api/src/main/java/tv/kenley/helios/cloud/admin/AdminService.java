package tv.kenley.helios.cloud.admin;

import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.smithy.java.framework.model.ValidationException;
import tv.kenley.helios.cloud.common.ChallengeKeys;
import tv.kenley.helios.cloud.common.Ulid;
import tv.kenley.helios.cloud.model.ChallengeKeyDescription;
import tv.kenley.helios.cloud.model.ChallengeSettings;
import tv.kenley.helios.cloud.model.DeviceDescription;
import tv.kenley.helios.cloud.model.DeviceNotDisabled;
import tv.kenley.helios.cloud.model.DeviceNotFound;
import tv.kenley.helios.cloud.model.DeviceStatus;
import tv.kenley.helios.cloud.model.InvalidChallengeKey;
import tv.kenley.helios.cloud.model.PutChallengeKeyInput;
import tv.kenley.helios.cloud.model.RegisterDeviceInput;
import tv.kenley.helios.cloud.model.RegistrationConflict;
import tv.kenley.helios.cloud.model.SetDevicePinInput;
import tv.kenley.helios.cloud.model.SetDeviceStatusInput;

/**
 * The admin API's operations (Bootstrap API, section 4): RegisterDevice, GetDevice,
 * SetDeviceStatus, SetDevicePin, PutChallengeKey and DeleteDevice. Errors are the modelled ones,
 * thrown as the generated exceptions.
 */
final class AdminService {
  private final AdminStore store;
  private final Clock clock;
  private final SecureRandom random;

  AdminService(AdminStore store, Clock clock, SecureRandom random) {
    this.store = store;
    this.clock = clock;
    this.random = random;
  }

  /** {@code RegisterDevice}. */
  DeviceDescription register(RegisterDeviceInput input) {
    Instant now = clock.instant();
    ChallengeSettings challenge =
        Optional.ofNullable(input.getChallenge()).orElse(ChallengeSettings.builder().build());
    Optional<String> pem = Optional.ofNullable(challenge.getPublicKeyPem());
    if (challenge.isRequired() && pem.isEmpty()) {
      throw ValidationException.builder()
          .message("challenge.required needs challenge.publicKeyPem")
          .build();
    }
    Optional<String> fingerprint = pem.map(AdminService::fingerprint);
    List<String> macs = input.getMacs().stream().distinct().sorted().toList();
    if (store.thingNameTaken(input.getThingName())) {
      throw RegistrationConflict.builder()
          .message("The thing name already belongs to another device record.")
          .conflictingThingName(input.getThingName())
          .build();
    }
    Map<String, AttributeValue> item =
        DeviceItems.item(
            new DeviceItems.NewDevice(
                Ulid.generate("d-", now, random),
                input.getThingName(),
                input.getRole(),
                macs,
                input.getPin(),
                input.getToken(),
                challenge.isRequired(),
                pem,
                fingerprint,
                Optional.ofNullable(input.getAws()),
                now));
    try {
      store.create(item, macs);
    } catch (AdminStore.MacConflict e) {
      throw RegistrationConflict.builder()
          .message("MAC addresses already belong to another device record.")
          .conflictingMacs(e.macs())
          .build();
    }
    return DeviceItems.describe(item);
  }

  /** {@code GetDevice}. */
  DeviceDescription get(String deviceId) {
    return DeviceItems.describe(store.get(deviceId).orElseThrow(() -> notFound(deviceId)));
  }

  /** {@code SetDeviceStatus}. */
  DeviceDescription setStatus(SetDeviceStatusInput input) {
    return store
        .setStatus(
            input.getDeviceId(),
            input.getStatus(),
            Optional.ofNullable(input.getReason()),
            clock.instant())
        .map(DeviceItems::describe)
        .orElseThrow(() -> notFound(input.getDeviceId()));
  }

  /**
   * {@code SetDevicePin}: the PIN the device's next bootstrap gets, after its token is
   * re-provisioned (call it before the device tries the new token).
   */
  void setPin(SetDevicePinInput input) {
    if (!store.setPin(input.getDeviceId(), input.getPin(), clock.instant())) {
      throw notFound(input.getDeviceId());
    }
  }

  /** {@code PutChallengeKey}: registers or replaces the challenge public key. */
  ChallengeKeyDescription putChallengeKey(PutChallengeKeyInput input) {
    String fingerprint = fingerprint(input.getPublicKeyPem());
    return store
        .setChallengeKey(input.getDeviceId(), input.getPublicKeyPem(), fingerprint, clock.instant())
        .map(item -> DeviceItems.describe(item).getChallenge())
        .orElseThrow(() -> notFound(input.getDeviceId()));
  }

  /** {@code DeleteDevice}: only while disabled; frees the record's MACs. */
  void delete(String deviceId) {
    DeviceDescription device = get(deviceId);
    if (!device.getStatus().equals(DeviceStatus.DISABLED)) {
      throw notDisabled();
    }
    try {
      store.delete(deviceId, device.getMacs());
    } catch (AdminStore.NotDisabled e) {
      throw notDisabled();
    }
  }

  private static String fingerprint(String pem) {
    ECPublicKey key;
    try {
      key = ChallengeKeys.parseP256(pem);
    } catch (IllegalArgumentException e) {
      throw InvalidChallengeKey.builder()
          .message("The public key isn't a P-256 SubjectPublicKeyInfo in PEM.")
          .build();
    }
    return ChallengeKeys.fingerprint(key);
  }

  private static DeviceNotFound notFound(String deviceId) {
    return DeviceNotFound.builder().message("No device record " + deviceId + ".").build();
  }

  private static DeviceNotDisabled notDisabled() {
    return DeviceNotDisabled.builder()
        .message("Devices can only be deleted while disabled.")
        .build();
  }
}
