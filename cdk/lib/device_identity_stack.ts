/**
 * @fileoverview The device identity resources: the AWS IoT policy attached to
 * every device certificate, and the IAM role and role alias that the AWS IoT
 * credentials provider uses to give devices temporary AWS credentials (HLD
 * sections 5.2, 5.3 and 9; LLD 1, section 5.5; LLD 2, section 8).
 */

import {ArnFormat, NestedStack} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {
  Effect,
  PolicyDocument,
  PolicyStatement,
  Role,
  ServicePrincipal,
} from 'aws-cdk-lib/aws-iam';
import {CfnPolicy, CfnRoleAlias} from 'aws-cdk-lib/aws-iot';
import type {Construct} from 'constructs';

/**
 * The name of the AWS IoT policy attached to each device certificate. The
 * provisioning script attaches it by this name.
 */
export const DEVICE_POLICY_NAME = 'helios-device-policy';

/**
 * The name of the role alias devices request credentials for. Helios's
 * default `role_alias` and the bootstrap service's response use this name.
 */
export const DEVICE_ROLE_ALIAS_NAME = 'helios-device-role-alias';

/** The service principal of the AWS IoT credentials provider. */
const CREDENTIALS_PROVIDER = 'credentials.iot.amazonaws.com';

/**
 * The connecting device's thing name in an AWS IoT policy. A device's client
 * ID is its thing name (HLD section 5.3).
 */
const IOT_THING = '${iot:Connection.Thing.ThingName}';

/**
 * The requesting device's thing name in the IAM policy of a role assumed
 * through the credentials provider.
 */
const CREDENTIALS_THING = '${credentials-iot:ThingName}';

/** The modules' named shadows, under `$aws/things/<thing>/shadow/name/`. */
const SHADOWS = `$aws/things/${IOT_THING}/shadow/name/*`;

/**
 * The nested stack for device identity. Each device can use only its own
 * client ID, topics and shadows, and its credentials can read only its own
 * secrets.
 */
export class DeviceIdentityStack extends NestedStack {
  /** The IAM role that devices' temporary credentials are for. */
  readonly deviceRole: Role;

  /** The role alias that points to {@link deviceRole}. */
  readonly roleAlias: CfnRoleAlias;

  /** The AWS IoT policy for device certificates. */
  readonly devicePolicy: CfnPolicy;

  constructor(scope: Construct, id: string, props?: NestedStackProps) {
    super(scope, id, {
      description: 'Helios device identity: IoT policy, IAM role, role alias',
      ...props,
    });

    this.deviceRole = new Role(this, 'DeviceRole', {
      assumedBy: new ServicePrincipal(CREDENTIALS_PROVIDER),
      description: 'Temporary AWS credentials for Helios devices',
    });
    // SRT passphrases (HLD section 6.4). The secret ARN ends in a random
    // suffix, hence the wildcard.
    this.deviceRole.addToPolicy(
      new PolicyStatement({
        actions: ['secretsmanager:GetSecretValue'],
        resources: [
          this.formatArn({
            service: 'secretsmanager',
            resource: 'secret',
            resourceName: `helios/srt/${CREDENTIALS_THING}*`,
            arnFormat: ArnFormat.COLON_RESOURCE_NAME,
          }),
        ],
      }),
    );

    // The credential lifetime stays at the default of 1 hour (LLD 1,
    // section 5.3).
    this.roleAlias = new CfnRoleAlias(this, 'DeviceRoleAlias', {
      roleAlias: DEVICE_ROLE_ALIAS_NAME,
      roleArn: this.deviceRole.roleArn,
    });

    this.devicePolicy = new CfnPolicy(this, 'DevicePolicy', {
      policyName: DEVICE_POLICY_NAME,
      policyDocument: this.devicePolicyDocument(),
    });
  }

  private devicePolicyDocument(): PolicyDocument {
    const iot = (resource: string, name: string) =>
      this.formatArn({service: 'iot', resource, resourceName: name});
    return new PolicyDocument({
      statements: [
        // The client ID must be the thing name of the thing the certificate
        // is attached to.
        new PolicyStatement({
          effect: Effect.ALLOW,
          actions: ['iot:Connect'],
          resources: [iot('client', IOT_THING)],
          conditions: {Bool: {'iot:Connection.Thing.IsAttached': 'true'}},
        }),
        // Command responses and events, including the lifecycle.offline last
        // will (HLD sections 5.3 and 7.1).
        new PolicyStatement({
          effect: Effect.ALLOW,
          actions: ['iot:Publish'],
          resources: [
            iot('topic', `helios/${IOT_THING}/rsp/*`),
            iot('topic', `helios/${IOT_THING}/evt/*`),
            iot('topic', `${SHADOWS}/get`),
            iot('topic', `${SHADOWS}/update`),
          ],
        }),
        // Commands, and the shadow service's replies and desired-state
        // deltas.
        new PolicyStatement({
          effect: Effect.ALLOW,
          actions: ['iot:Subscribe'],
          resources: [
            iot('topicfilter', `helios/${IOT_THING}/cmd/*`),
            iot('topicfilter', `${SHADOWS}/get/*`),
            iot('topicfilter', `${SHADOWS}/update/*`),
          ],
        }),
        new PolicyStatement({
          effect: Effect.ALLOW,
          actions: ['iot:Receive'],
          resources: [
            iot('topic', `helios/${IOT_THING}/cmd/*`),
            iot('topic', `${SHADOWS}/get/*`),
            iot('topic', `${SHADOWS}/update/*`),
          ],
        }),
        // Temporary AWS credentials from the credentials provider.
        new PolicyStatement({
          effect: Effect.ALLOW,
          actions: ['iot:AssumeRoleWithCertificate'],
          resources: [this.roleAlias.attrRoleAliasArn],
        }),
      ],
    });
  }
}
