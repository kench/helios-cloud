package tv.kenley.helios.cloud.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowResponse;
import software.amazon.awssdk.services.iotdataplane.model.ResourceNotFoundException;

class IotShadowsTest {
  @Test
  void getsNamedShadowOrNone() {
    IotDataPlaneClient iot = mock(IotDataPlaneClient.class);
    when(iot.getThingShadow(any(GetThingShadowRequest.class)))
        .thenReturn(
            GetThingShadowResponse.builder()
                .payload(SdkBytes.fromUtf8String("{\"version\":3}"))
                .build())
        .thenThrow(ResourceNotFoundException.builder().message("No shadow exists").build());
    IotShadows shadows = new IotShadows(iot);
    assertArrayEquals(
        "{\"version\":3}".getBytes(StandardCharsets.UTF_8),
        shadows.get("t-1", "stream").orElseThrow());
    assertTrue(shadows.get("t-1", "stream").isEmpty());
    ArgumentCaptor<GetThingShadowRequest> request =
        ArgumentCaptor.forClass(GetThingShadowRequest.class);
    verify(iot, org.mockito.Mockito.times(2)).getThingShadow(request.capture());
    assertEquals("stream", request.getValue().shadowName());
    assertEquals("t-1", request.getValue().thingName());
  }
}
