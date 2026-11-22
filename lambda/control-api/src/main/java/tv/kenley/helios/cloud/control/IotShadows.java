package tv.kenley.helios.cloud.control;

import java.util.Optional;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iotdataplane.model.UpdateThingShadowRequest;

/** {@link Shadows} through AWS IoT's data plane (GetThingShadow, UpdateThingShadow). */
final class IotShadows implements Shadows {
  private final IotDataPlaneClient iot;

  IotShadows(IotDataPlaneClient iot) {
    this.iot = iot;
  }

  @Override
  public Optional<byte[]> get(String thingName, String name) {
    try {
      return Optional.of(
          iot.getThingShadow(
                  GetThingShadowRequest.builder().thingName(thingName).shadowName(name).build())
              .payload()
              .asByteArray());
    } catch (ResourceNotFoundException e) {
      return Optional.empty();
    }
  }

  @Override
  public long update(String thingName, String name, byte[] document) {
    byte[] response =
        iot.updateThingShadow(
                UpdateThingShadowRequest.builder()
                    .thingName(thingName)
                    .shadowName(name)
                    .payload(SdkBytes.fromByteArray(document))
                    .build())
            .payload()
            .asByteArray();
    return ShadowJson.whole(ShadowJson.parse(response), "version").orElse(0L);
  }
}
