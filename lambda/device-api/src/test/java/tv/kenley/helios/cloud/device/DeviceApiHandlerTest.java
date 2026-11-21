package tv.kenley.helios.cloud.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tv.kenley.helios.cloud.common.RestJson;

@ExtendWith(MockitoExtension.class)
final class DeviceApiHandlerTest {
  @Mock private DeviceApi api;
  @Mock private Context context;

  private static APIGatewayProxyRequestEvent event(String body, boolean base64) {
    return new APIGatewayProxyRequestEvent()
        .withHttpMethod("POST")
        .withPath(DeviceApi.BOOTSTRAP_PATH)
        .withBody(body)
        .withIsBase64Encoded(base64)
        .withRequestContext(
            new APIGatewayProxyRequestEvent.ProxyRequestContext()
                .withIdentity(
                    new APIGatewayProxyRequestEvent.RequestIdentity().withSourceIp("203.0.113.7")));
  }

  @Test
  void passesTheRequestThroughAndReturnsTheResponse() {
    ArgumentCaptor<RestJson.Request> request = ArgumentCaptor.forClass(RestJson.Request.class);
    when(context.getAwsRequestId()).thenReturn("request-1");
    when(api.handle(request.capture()))
        .thenReturn(new RestJson.Response(200, Map.of("Cache-Control", "no-store"), "{}"));

    APIGatewayProxyResponseEvent response =
        new DeviceApiHandler(api).handleRequest(event("{\"a\":1}", false), context);

    assertEquals(200, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().get("Cache-Control"));
    assertEquals("{}", response.getBody());
    assertFalse(response.getIsBase64Encoded());
    RestJson.Request seen = request.getValue();
    assertEquals("POST", seen.method());
    assertEquals(DeviceApi.BOOTSTRAP_PATH, seen.path());
    assertEquals("203.0.113.7", seen.sourceIp());
    assertEquals("request-1", seen.requestId());
    assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), seen.body());
  }

  @Test
  void decodesBase64BodiesAndToleratesMissingParts() {
    ArgumentCaptor<RestJson.Request> request = ArgumentCaptor.forClass(RestJson.Request.class);
    when(api.handle(request.capture())).thenReturn(new RestJson.Response(200, Map.of(), "{}"));
    String encoded = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
    APIGatewayProxyRequestEvent noContext = event(encoded, true).withRequestContext(null);

    new DeviceApiHandler(api).handleRequest(noContext, context);
    new DeviceApiHandler(api).handleRequest(event(null, false), context);

    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), request.getAllValues().get(0).body());
    assertEquals("unknown", request.getAllValues().get(0).sourceIp());
    assertEquals(0, request.getAllValues().get(1).body().length);
  }

  @Test
  void theDefaultConstructorNeedsItsSettings() {
    // Outside Lambda the table variables aren't set.
    assertThrows(IllegalArgumentException.class, DeviceApiHandler::new);
  }
}
