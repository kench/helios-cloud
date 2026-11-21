/**
 * @fileoverview The operator role: what people and tools need to send Helios
 * commands to devices and read their responses (LLD 2, section 7.2), over
 * AWS IoT's custom topics.
 *
 * Commands are published to `helios/<thing>/cmd/<module>`, with the AWS CLI
 * (`aws iot-data publish`) or MQTT. Responses arrive on
 * `helios/<thing>/rsp/<module>`, which only an MQTT client (over WebSocket,
 * signed with these credentials) can subscribe to; its client ID must start
 * with `helios-operator-`, so it can't take over a device's connection.
 */

import {NestedStack} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {AccountPrincipal, Grant, Role} from 'aws-cdk-lib/aws-iam';
import type {IGrantable} from 'aws-cdk-lib/aws-iam';
import type {Construct} from 'constructs';

/** The operator role's name, for `aws sts assume-role` and CLI profiles. */
export const OPERATOR_ROLE_NAME = 'helios-operator';

/** The prefix of operator MQTT client IDs. */
export const OPERATOR_CLIENT_PREFIX = 'helios-operator-';

/** The nested stack for operating devices. */
export class OperatorStack extends NestedStack {
  /** The role people and tools assume to send commands. */
  readonly operatorRole: Role;

  constructor(scope: Construct, id: string, props?: NestedStackProps) {
    super(scope, id, {
      description: 'Helios operator: role for sending device commands',
      ...props,
    });

    this.operatorRole = new Role(this, 'OperatorRole', {
      roleName: OPERATOR_ROLE_NAME,
      // Any identity in this account that its own policy allows to assume
      // the role. The trust doesn't grant anything by itself.
      assumedBy: new AccountPrincipal(this.account),
      description: 'Send Helios device commands and read their responses',
    });
    this.grantCommands(this.operatorRole);
  }

  /**
   * Lets `grantee` publish commands to every device and receive the
   * responses: connect as an operator client, publish to `cmd`, and
   * subscribe to and receive `rsp`.
   */
  grantCommands(grantee: IGrantable): void {
    const iot = (resource: string, name: string) =>
      this.formatArn({service: 'iot', resource, resourceName: name});
    const grants: Array<[string, string[]]> = [
      ['iot:Connect', [iot('client', `${OPERATOR_CLIENT_PREFIX}*`)]],
      ['iot:Publish', [iot('topic', 'helios/*/cmd/*')]],
      ['iot:Subscribe', [iot('topicfilter', 'helios/*/rsp/*')]],
      ['iot:Receive', [iot('topic', 'helios/*/rsp/*')]],
    ];
    for (const [action, resourceArns] of grants) {
      Grant.addToPrincipal({grantee, actions: [action], resourceArns});
    }
  }
}
