package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tv.kenley.helios.cloud.control.model.InvalidDestination;

class DestinationsTest {
  @Test
  void readsAnSrtUrlAndWritesItsNormalForm() {
    Destinations.Destination d =
        Destinations.parse(
            "srt://ingest.example.com:4001?passphrase=not-a-secret-123&latency=2000"
                + "&streamid=publish/live/pocket");
    assertFalse(d.bonded());
    assertEquals("ingest.example.com", d.host());
    assertEquals(4001, d.port());
    assertEquals("publish/live/pocket", d.streamId());
    assertEquals(2000, d.latencyMs());
    assertEquals("not-a-secret-123", d.passphrase());
    assertEquals(
        "srt://ingest.example.com:4001?streamid=publish/live/pocket&latency=2000"
            + "&passphrase=not-a-secret-123",
        d.url());
  }

  @Test
  void readsSrtlaIpv6AndPercentEscapes() {
    Destinations.Destination bonded = Destinations.parse("srtla://10.0.0.1:5000");
    assertTrue(bonded.bonded());
    assertNull(bonded.streamId());
    assertNull(bonded.latencyMs());
    assertEquals("srtla://10.0.0.1:5000", bonded.url());

    Destinations.Destination v6 =
        Destinations.parse(
            "srt://[2001:db8::1]:4001?streamid=publish%2Flive%2Fa&passphrase=a+b%21cdefghij");
    assertEquals("2001:db8::1", v6.host());
    assertEquals("publish/live/a", v6.streamId());
    assertEquals("a+b!cdefghij", v6.passphrase(), "+ is a plus sign, not a space");
    assertEquals(
        "srt://[2001:db8::1]:4001?streamid=publish/live/a&passphrase=a+b!cdefghij", v6.url());
  }

  @Test
  void redactsPassphrasesForLogs() {
    assertEquals(
        "srt://h:1?streamid=a&passphrase=***&latency=200",
        Destinations.redact("srt://h:1?streamid=a&passphrase=not-a-secret-123&latency=200"));
    assertEquals("srtla://h:1?passphrase=***", Destinations.redact("srtla://h:1?passphrase=x%21y"));
    assertEquals(
        "srt://h:1?passphrase=***&passphrase=***#f",
        Destinations.redact("srt://h:1?passphrase=a&passphrase=b#f"),
        "every one, even in an invalid URL");
    assertEquals("srt://h:1?latency=200", Destinations.redact("srt://h:1?latency=200"));
    assertEquals(
        "srt://h:1?mypassphrase=x",
        Destinations.redact("srt://h:1?mypassphrase=x"),
        "only the parameter itself");
    assertNull(Destinations.redact(null));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "rtmp://host:1935/live|scheme",
        "srt://host|port",
        "srt://host:0|port",
        "srt://host:70000|port",
        "srt://host:12a|port",
        "srt://ho st:1|host",
        "srt://:1|host",
        "srt://[2001:db8::1:4001|host",
        "srt://[zz]:4001|host",
        "srt://host:1?mode=caller|mode",
        "srt://host:1?=x|parameter",
        "srt://host:1?latency|latency",
        "srt://host:1?latency=10|latency",
        "srt://host:1?latency=30001|latency",
        "srt://host:1?latency=2000&latency=3000|latency",
        "srt://host:1?passphrase=short|passphrase",
        "srt://host:1?passphrase=has%20space123|passphrase",
        "srt://host:1?streamid=a%26b|streamid",
        "srt://host:1?streamid=a%2|streamid",
        "srt://host:1?streamid=a%zz|streamid",
        "srt://host:1?streamid=café|streamid",
        "srt://host:1#top|fragment",
      })
  void refusesAnythingElseAndNamesThePart(String url, String part) {
    InvalidDestination e = assertThrows(InvalidDestination.class, () -> Destinations.parse(url));
    assertEquals(part, e.getPart(), url);
  }
}
