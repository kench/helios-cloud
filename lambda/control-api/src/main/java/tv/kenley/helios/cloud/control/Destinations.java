package tv.kenley.helios.cloud.control;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tv.kenley.helios.cloud.control.model.InvalidDestination;

/**
 * SRT and SRTLA destination URLs (the contract's {@code DestinationUrl}, HLD section 7.3.2): the
 * only destinations operators may set. The same rules as the stream daemon's outputs, which checks
 * them again: {@code srt://} or {@code srtla://}, a host name or IP address, a port, and only
 * {@code streamid}, {@code latency} (milliseconds) and {@code passphrase}, each at most once.
 */
final class Destinations {
  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._-]{1,253}$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
  private static final Pattern STREAM_ID = Pattern.compile("^[A-Za-z0-9/_.:=,-]{1,512}$");
  private static final Pattern PASSPHRASE = Pattern.compile("^[A-Za-z0-9._~!$'()*+,;:@-]{10,79}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,5}$");

  /** The order parameters are written in, in a {@link Destination#url()}. */
  private static final List<String> PARAMETERS = List.of("streamid", "latency", "passphrase");

  /** A {@code passphrase} parameter's value, up to the next parameter or fragment. */
  private static final Pattern PASSPHRASE_VALUE = Pattern.compile("([?&]passphrase=)[^&#]*");

  private Destinations() {}

  /**
   * {@code url} with any SRT passphrase replaced by {@code ***}, for logs: works on any string,
   * valid destination or not, since the audit line records what the caller sent.
   */
  static String redact(String url) {
    return url == null ? null : PASSPHRASE_VALUE.matcher(url).replaceAll("$1***");
  }

  /**
   * A checked destination.
   *
   * @param bonded {@code srtla} rather than {@code srt}
   * @param host the host name or IP address (an IPv6 address without brackets)
   * @param port the port
   * @param streamId the SRT stream ID, or {@code null}
   * @param latencyMs the SRT latency in milliseconds, or {@code null} for the device's default
   * @param passphrase the SRT passphrase, or {@code null}
   */
  record Destination(
      boolean bonded,
      String host,
      int port,
      String streamId,
      Integer latencyMs,
      String passphrase) {
    /** The URL in its normal form: the parameters in a fixed order, nothing percent-encoded. */
    String url() {
      Map<String, String> values = new LinkedHashMap<>();
      values.put("streamid", streamId);
      values.put("latency", latencyMs == null ? null : latencyMs.toString());
      values.put("passphrase", passphrase);
      List<String> query = new ArrayList<>();
      for (String name : PARAMETERS) {
        if (values.get(name) != null) {
          query.add(name + "=" + values.get(name));
        }
      }
      String hostPart = host.contains(":") ? "[" + host + "]" : host;
      return (bonded ? "srtla" : "srt")
          + "://"
          + hostPart
          + ":"
          + port
          + (query.isEmpty() ? "" : "?" + String.join("&", query));
    }
  }

  /**
   * Parses and checks {@code url}.
   *
   * @throws InvalidDestination naming the part that's wrong
   */
  static Destination parse(String url) {
    boolean bonded;
    String rest;
    if (url.startsWith("srt://")) {
      bonded = false;
      rest = url.substring("srt://".length());
    } else if (url.startsWith("srtla://")) {
      bonded = true;
      rest = url.substring("srtla://".length());
    } else {
      throw invalid("scheme", "The destination must be an srt:// or srtla:// URL.");
    }
    if (rest.contains("#")) {
      throw invalid("fragment", "The destination can't have a #fragment.");
    }
    int question = rest.indexOf('?');
    String authority = question < 0 ? rest : rest.substring(0, question);
    String query = question < 0 ? "" : rest.substring(question + 1);

    String host;
    String port;
    if (authority.startsWith("[")) {
      int close = authority.indexOf(']');
      if (close < 0 || !authority.startsWith(":", close + 1)) {
        throw invalid("host", "An IPv6 address needs brackets, then :port.");
      }
      host = authority.substring(1, close);
      port = authority.substring(close + 2);
      if (!IPV6.matcher(host).matches()) {
        throw invalid("host", "Not an IPv6 address.");
      }
    } else {
      int colon = authority.lastIndexOf(':');
      if (colon < 0) {
        throw invalid("port", "The destination needs a port: host:port.");
      }
      host = authority.substring(0, colon);
      port = authority.substring(colon + 1);
      if (!HOST.matcher(host).matches()) {
        throw invalid("host", "The host must be a host name or IP address.");
      }
    }
    if (!DIGITS.matcher(port).matches()
        || Integer.parseInt(port) < 1
        || Integer.parseInt(port) > 65535) {
      throw invalid("port", "The port must be 1 to 65535.");
    }

    Map<String, String> parameters = new LinkedHashMap<>();
    if (!query.isEmpty()) {
      for (String pair : query.split("&", -1)) {
        int equals = pair.indexOf('=');
        String name = equals < 0 ? pair : pair.substring(0, equals);
        if (!PARAMETERS.contains(name)) {
          throw invalid(
              name.isEmpty() ? "parameter" : name,
              "Only streamid, latency and passphrase are allowed.");
        }
        if (equals < 0) {
          throw invalid(name, name + " needs a value.");
        }
        if (parameters.containsKey(name)) {
          throw invalid(name, name + " is given more than once.");
        }
        parameters.put(name, decode(pair.substring(equals + 1), name));
      }
    }

    String streamId = parameters.get("streamid");
    if (streamId != null && !STREAM_ID.matcher(streamId).matches()) {
      throw invalid(
          "streamid", "streamid must be up to 512 letters, digits and / _ . - : = , characters.");
    }
    Integer latencyMs = null;
    String latency = parameters.get("latency");
    if (latency != null) {
      if (!DIGITS.matcher(latency).matches()
          || Integer.parseInt(latency) < 20
          || Integer.parseInt(latency) > 30000) {
        throw invalid("latency", "latency must be 20 to 30000 milliseconds.");
      }
      latencyMs = Integer.parseInt(latency);
    }
    String passphrase = parameters.get("passphrase");
    if (passphrase != null && !PASSPHRASE.matcher(passphrase).matches()) {
      throw invalid(
          "passphrase",
          "passphrase must be 10 to 79 letters, digits and - . _ ~ ! $ ' ( ) * + , ; : @"
              + " characters.");
    }
    return new Destination(bonded, host, Integer.parseInt(port), streamId, latencyMs, passphrase);
  }

  /** Percent-decoding as in RFC 3986: {@code +} is a plus sign, not a space. */
  private static String decode(String value, String name) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '%') {
        if (i + 2 >= value.length()) {
          throw invalid(name, "A % escape needs two hex digits.");
        }
        int high = Character.digit(value.charAt(i + 1), 16);
        int low = Character.digit(value.charAt(i + 2), 16);
        if (high < 0 || low < 0) {
          throw invalid(name, "A % escape needs two hex digits.");
        }
        out.write(high * 16 + low);
        i += 2;
      } else if (c > 0x7e || c < 0x21) {
        throw invalid(name, name + " has a character that must be percent-encoded.");
      } else {
        out.write(c);
      }
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static InvalidDestination invalid(String part, String message) {
    return InvalidDestination.builder().part(part).message(message).build();
  }
}
