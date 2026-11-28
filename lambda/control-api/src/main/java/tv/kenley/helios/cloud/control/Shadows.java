package tv.kenley.helios.cloud.control;

import java.util.Optional;

/** Devices' named shadows, as the Control API reads them; it never writes one. */
interface Shadows {
  /** The shadow {@code name} of {@code thingName}, or empty if it has none. */
  Optional<byte[]> get(String thingName, String name);
}
