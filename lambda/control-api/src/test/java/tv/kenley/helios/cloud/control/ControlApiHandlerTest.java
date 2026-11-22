package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tv.kenley.helios.cloud.common.RestJson;

class ControlApiHandlerTest {
  @Test
  void passesTheRequestAndItsQueryToTheApi() {
    ControlApi api = mock(ControlApi.class);
    when(api.handle(any(), any())).thenReturn(new RestJson.Response(200, Map.of(), "{}"));
    Context context = mock(Context.class);
    when(context.getAwsRequestId()).thenReturn("req-1");
    APIGatewayProxyRequestEvent event =
        new APIGatewayProxyRequestEvent()
            .withHttpMethod("GET")
            .withPath("/v1/control/devices")
            .withQueryStringParameters(Map.of("maxResults", "5"));
    assertEquals(200, new ControlApiHandler(api).handleRequest(event, context).getStatusCode());
    verify(api).handle(any(), eq(Map.of("maxResults", "5")));

    APIGatewayProxyRequestEvent noQuery =
        new APIGatewayProxyRequestEvent().withHttpMethod("GET").withPath("/v1/control/devices");
    new ControlApiHandler(api).handleRequest(noQuery, context);
    verify(api).handle(any(), eq(Map.of()));
  }

  @Test
  void readsItsEnvironment() {
    assertEquals(
        URI.create("https://example-ats.iot.us-west-2.amazonaws.com"),
        ControlApiHandler.iotEndpoint(
            Map.of("IOT_DATA_ENDPOINT", "example-ats.iot.us-west-2.amazonaws.com")));
    assertThrows(IllegalArgumentException.class, () -> ControlApiHandler.iotEndpoint(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ControlApiHandler.required(Map.of("DEVICES_TABLE", " "), "DEVICES_TABLE"));
  }
}
