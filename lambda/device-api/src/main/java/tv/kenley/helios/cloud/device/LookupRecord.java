package tv.kenley.helios.cloud.device;

import java.time.Instant;
import java.util.Optional;

/**
 * The last lookup of a device, stored as its {@code lastSeen} (Bootstrap API, section 5).
 *
 * @param time when it happened
 * @param result a {@code LookupResult} value, such as {@code identity_returned}
 * @param sourceIp the caller's address
 * @param heliosVersion the version Helios reported, from {@code Bootstrap}
 * @param hardwareModel the hardware model Helios reported, from {@code Bootstrap}
 */
record LookupRecord(
    Instant time,
    String result,
    String sourceIp,
    Optional<String> heliosVersion,
    Optional<String> hardwareModel) {}
