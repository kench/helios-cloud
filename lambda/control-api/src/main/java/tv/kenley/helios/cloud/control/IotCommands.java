package tv.kenley.helios.cloud.control;

import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CommandExecutionResult;
import software.amazon.awssdk.services.iot.model.GetCommandExecutionResponse;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iotjobsdataplane.IotJobsDataPlaneClient;
import software.amazon.awssdk.services.iotjobsdataplane.model.CommandParameterValue;

/**
 * AWS IoT command executions: started on the IoT data endpoint (iot-jobs-data), read from the
 * control plane. {@code arnPrefix} is {@code arn:aws:iot:<region>:<account>}.
 */
final class IotCommands implements Commands {
  private final IotJobsDataPlaneClient data;
  private final IotClient control;
  private final String arnPrefix;

  IotCommands(IotJobsDataPlaneClient data, IotClient control, String arnPrefix) {
    this.data = data;
    this.control = control;
    this.arnPrefix = arnPrefix;
  }

  private String thingArn(String thingName) {
    return arnPrefix + ":thing/" + thingName;
  }

  @Override
  public String start(String thingName, String commandId, String args, int timeoutSeconds) {
    return data.startCommandExecution(request ->
            request
                .targetArn(thingArn(thingName))
                .commandArn(arnPrefix + ":command/" + commandId)
                .parameters(Map.of("args", CommandParameterValue.builder().s(args).build()))
                .executionTimeoutSeconds((long) timeoutSeconds))
        .executionId();
  }

  @Override
  public Optional<Execution> get(String thingName, String executionId) {
    GetCommandExecutionResponse response;
    try {
      response =
          control.getCommandExecution(request ->
              request.executionId(executionId).targetArn(thingArn(thingName)).includeResult(true));
    } catch (ResourceNotFoundException e) {
      return Optional.empty();
    }
    String reasonCode = null;
    String reasonDescription = null;
    if (response.statusReason() != null) {
      reasonCode = response.statusReason().reasonCode();
      reasonDescription = response.statusReason().reasonDescription();
    }
    String result = null;
    if (response.result() != null) {
      CommandExecutionResult json = response.result().get("json");
      if (json != null) {
        result = json.s();
      }
    }
    return Optional.of(
        new Execution(response.statusAsString(), reasonCode, reasonDescription, result));
  }
}
