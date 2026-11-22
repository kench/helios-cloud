package tv.kenley.helios.cloud.control;

import java.util.Optional;

/** Devices' named shadows, as the Control API uses them. */
interface Shadows {
  /** The shadow {@code name} of {@code thingName}, or empty if it has none. */
  Optional<byte[]> get(String thingName, String name);

  /**
   * Writes {@code document} ({@code {"state": {"desired": ...}, "clientToken": ...}}) to the shadow
   * {@code name} of {@code thingName}, and returns the shadow's new version.
   */
  long update(String thingName, String name, byte[] document);
}
