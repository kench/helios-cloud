package tv.kenley.helios.cloud.device;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import software.amazon.smithy.java.core.error.ModeledException;
import tv.kenley.helios.cloud.model.Adapter;
import tv.kenley.helios.cloud.model.AmbiguousDevice;
import tv.kenley.helios.cloud.model.AnswerChallengeInput;
import tv.kenley.helios.cloud.model.AwsSettings;
import tv.kenley.helios.cloud.model.BootstrapInput;
import tv.kenley.helios.cloud.model.BootstrapResult;
import tv.kenley.helios.cloud.model.Capability;
import tv.kenley.helios.cloud.model.Challenge;
import tv.kenley.helios.cloud.model.ChallengeExpired;
import tv.kenley.helios.cloud.model.ChallengeFailed;
import tv.kenley.helios.cloud.model.ChallengeUnsupported;
import tv.kenley.helios.cloud.model.DeviceDisabled;
import tv.kenley.helios.cloud.model.DeviceIdentity;
import tv.kenley.helios.cloud.model.SignatureAlgorithm;
import tv.kenley.helios.cloud.model.UnknownDevice;

/**
 * The device API's logic (Bootstrap API, section 3): matching a device by its MACs, issuing and
 * checking challenges, and building the identity. Errors are the modelled ones, thrown as the
 * generated exceptions.
 */
final class BootstrapService {
  /** How long a challenge stays valid (Bootstrap API, section 3.2). */
  static final Duration CHALLENGE_LIFETIME = Duration.ofSeconds(60);

  private static final int NONCE_BYTES = 32;

  private final DeviceStore store;
  private final Optional<AwsSettings> defaultAws;
  private final Clock clock;
  private final SecureRandom random;
  private final Consumer<String> warnings;

  /**
   * A service over {@code store}. {@code defaultAws} is sent to devices whose record has no AWS
   * settings of its own. {@code warnings} receives problems worth logging that don't fail the call.
   */
  BootstrapService(
      DeviceStore store,
      Optional<AwsSettings> defaultAws,
      Clock clock,
      SecureRandom random,
      Consumer<String> warnings) {
    this.store = store;
    this.defaultAws = defaultAws;
    this.clock = clock;
    this.random = random;
    this.warnings = warnings;
  }

  /** {@code Bootstrap}: the identity, or a challenge if the record requires one (3.1). */
  BootstrapResult bootstrap(BootstrapInput input, AuditEntry audit) {
    List<String> macs = macs(input.getAdapters());
    audit.macs(macs);
    Map<String, String> registered = store.deviceIdsForMacs(macs);
    if (registered.isEmpty()) {
      throw UnknownDevice.builder().message("No registered device has these MACs.").build();
    }
    Set<String> deviceIds = new TreeSet<>(registered.values());
    if (deviceIds.size() > 1) {
      throw AmbiguousDevice.builder()
          .message("These MACs belong to more than one device record.")
          .build();
    }
    String deviceId = deviceIds.iterator().next();
    audit.deviceId(deviceId);
    // MACs the record doesn't list are logged but don't block the match.
    audit.unregisteredMacs(macs.stream().filter(mac -> !registered.containsKey(mac)).toList());
    DeviceRecord device =
        store
            .device(deviceId)
            .orElseThrow(() -> {
              warnings.accept("MAC index points to missing device record " + deviceId);
              return UnknownDevice.builder()
                  .message("No registered device has these MACs.")
                  .build();
            });
    LookupDetails details =
        new LookupDetails(
            Optional.ofNullable(input.getHeliosVersion()),
            Optional.ofNullable(input.getHardwareModel()));
    return recorded(
        device,
        audit,
        details,
        () -> {
          checkEnabled(device);
          if (!device.challengeRequired()) {
            return new Outcome<>(
                BootstrapResult.builder().identity(identity(device)).build(), "identity");
          }
          if (!input.hasCapabilities()
              || !input.getCapabilities().contains(Capability.TOKEN_CHALLENGE)) {
            throw ChallengeUnsupported.builder()
                .message("This device must answer a challenge, but didn't offer token-challenge.")
                .build();
          }
          if (device.challengePublicKeyPem().isEmpty()) {
            throw new IllegalStateException(
                "device " + deviceId + " requires a challenge but has no challenge key");
          }
          audit.challenge();
          return new Outcome<>(
              BootstrapResult.builder().challenge(issue(device, macs)).build(), "challenge");
        });
  }

  /** {@code AnswerChallenge}: the identity, once the signature checks out (3.2). */
  DeviceIdentity answerChallenge(AnswerChallengeInput input, AuditEntry audit) {
    List<String> macs = macs(input.getAdapters());
    audit.macs(macs);
    audit.challenge();
    // Marked used before anything is checked, so a challenge can't be tried twice.
    ChallengeRecord challenge =
        store.useChallenge(input.getChallengeId()).orElseThrow(BootstrapService::expired);
    audit.deviceId(challenge.deviceId());
    if (!clock.instant().isBefore(challenge.expiresAt())) {
      throw expired();
    }
    DeviceRecord device =
        store.device(challenge.deviceId()).orElseThrow(() -> failed("Device record not found."));
    return recorded(
        device,
        audit,
        new LookupDetails(Optional.empty(), Optional.empty()),
        () -> {
          if (!macs.equals(challenge.macs())) {
            throw failed("The adapters don't match the original request.");
          }
          String key =
              device.challengePublicKeyPem().orElseThrow(() -> failed("No challenge key."));
          byte[] message =
              ChallengeSignature.message(
                  challenge.challengeId(), challenge.nonceBase64(), challenge.macs());
          if (!ChallengeSignature.verify(key, message, bytes(input.getSignature()))) {
            throw failed("The signature doesn't verify.");
          }
          checkEnabled(device);
          return new Outcome<>(identity(device), "identity");
        });
  }

  private Challenge issue(DeviceRecord device, List<String> macs) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    Instant now = clock.instant();
    ChallengeRecord challenge =
        new ChallengeRecord(
            Ulid.generate("c-", now, random),
            device.deviceId(),
            macs,
            Base64.getEncoder().encodeToString(nonce),
            // Whole seconds, as stored.
            now.plus(CHALLENGE_LIFETIME).truncatedTo(ChronoUnit.SECONDS));
    store.putChallenge(challenge);
    return Challenge.builder()
        .challengeId(challenge.challengeId())
        .nonce(ByteBuffer.wrap(nonce))
        .alg(SignatureAlgorithm.ECDSA_P256_SHA256)
        .expiresAt(challenge.expiresAt())
        .build();
  }

  private DeviceIdentity identity(DeviceRecord device) {
    return DeviceIdentity.builder()
        .deviceId(device.deviceId())
        .thingName(device.thingName())
        .pin(device.pin())
        .token(device.token())
        .aws(device.aws().or(() -> defaultAws).orElse(null))
        .build();
  }

  /** Runs {@code action} and records its result, or the error it throws, as lastSeen. */
  private <T> T recorded(
      DeviceRecord device,
      AuditEntry audit,
      LookupDetails details,
      java.util.function.Supplier<Outcome<T>> action) {
    try {
      Outcome<T> outcome = action.get();
      record(device, audit, details, outcome.result());
      return outcome.value();
    } catch (ModeledException e) {
      record(device, audit, details, e.schema().id().getName());
      throw e;
    }
  }

  private void record(DeviceRecord device, AuditEntry audit, LookupDetails details, String result) {
    try {
      store.recordLookup(
          device.deviceId(),
          new LookupRecord(
              audit.time(),
              result,
              audit.sourceIp(),
              details.heliosVersion(),
              details.hardwareModel()));
    } catch (RuntimeException e) {
      // lastSeen is for operators; failing to write it mustn't fail the device.
      warnings.accept("couldn't record lastSeen for " + device.deviceId() + ": " + e);
    }
  }

  private static void checkEnabled(DeviceRecord device) {
    if (device.disabled()) {
      throw DeviceDisabled.builder().message("Device is disabled.").build();
    }
  }

  /** The adapters' MACs, sorted ascending, without duplicates. */
  private static List<String> macs(List<Adapter> adapters) {
    return adapters.stream().map(Adapter::getMac).distinct().sorted().toList();
  }

  private static byte[] bytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] out = new byte[copy.remaining()];
    copy.get(out);
    return out;
  }

  private static ChallengeExpired expired() {
    return ChallengeExpired.builder()
        .message("The challenge has expired or was already used.")
        .build();
  }

  private static ChallengeFailed failed(String message) {
    return ChallengeFailed.builder().message(message).build();
  }

  private record Outcome<T>(T value, String result) {}

  private record LookupDetails(Optional<String> heliosVersion, Optional<String> hardwareModel) {}
}
