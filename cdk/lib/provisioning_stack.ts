/**
 * @fileoverview The provisioning role and user: what `provision-scanner.sh`
 * and other provisioning scripts need to create one device's AWS IoT
 * resources, and nothing else (Device Certificate Provisioning, Option A),
 * plus reading the devices' named shadows to check them.
 *
 * The `helios-provisioner` IAM user can do nothing but assume the role (and
 * `helios-operator`) and sign in to the AWS CLI with `aws login`, so provisioning doesn't use the
 * account root user (which can't assume roles anyway). The user has no
 * password or access keys from CDK: its console password and MFA device are
 * set in the IAM console, and the CLI gets short-lived credentials from
 * `aws login`.
 */

import {NestedStack} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {
  AccountPrincipal,
  ManagedPolicy,
  PolicyStatement,
  Role,
  User,
} from 'aws-cdk-lib/aws-iam';
import type {Construct} from 'constructs';

import {OPERATOR_ROLE_NAME} from './operator_stack';

/** The provisioning role's name, for `aws sts assume-role` and CLI profiles. */
export const PROVISIONING_ROLE_NAME = 'helios-provisioning';

/** The IAM user people sign in as to provision devices. */
export const PROVISIONER_USER_NAME = 'helios-provisioner';

/** AWS's managed policy for `aws login` with an IAM identity. */
const CLI_SIGN_IN_POLICY = 'SignInLocalDevelopmentAccess';

/**
 * The nested stack for provisioning. The role can create things and
 * certificates and attach them, and read named shadows, but can't change,
 * detach or delete anything.
 */
export class ProvisioningStack extends NestedStack {
  /** The role provisioning scripts assume. */
  readonly provisioningRole: Role;

  /** The user that assumes it. */
  readonly provisioner: User;

  constructor(scope: Construct, id: string, props?: NestedStackProps) {
    super(scope, id, {
      description:
        'Helios provisioning: role and user for per-device IoT resources',
      ...props,
    });

    this.provisioningRole = new Role(this, 'ProvisioningRole', {
      roleName: PROVISIONING_ROLE_NAME,
      // Any identity in this account that its own policy allows to assume
      // the role. The trust doesn't grant anything by itself.
      assumedBy: new AccountPrincipal(this.account),
      description: 'Create AWS IoT things and certificates for Helios devices',
    });
    const iot = (resource: string) =>
      this.formatArn({service: 'iot', resource, resourceName: '*'});
    for (const statement of [
      new PolicyStatement({
        actions: ['iot:CreateThing'],
        resources: [iot('thing')],
      }),
      // These two have no resource type: the certificate doesn't exist yet,
      // and endpoints are per account.
      new PolicyStatement({
        actions: ['iot:CreateCertificateFromCsr', 'iot:DescribeEndpoint'],
        resources: ['*'],
      }),
      // Both are authorized against the certificate. IAM can't limit which
      // IoT policy AttachPolicy attaches, since the policy isn't one of its
      // resource types; helios-device-policy is the only one in the account.
      new PolicyStatement({
        actions: ['iot:AttachThingPrincipal', 'iot:AttachPolicy'],
        resources: [iot('cert')],
      }),
      // Read-only: a device's named shadows (`thing/<thing>/<shadow>`), to
      // check a new or upgraded device, for example that no desired state is
      // left. Operators read devices through the Control API instead, not raw
      // shadows (passphrases, exact position).
      new PolicyStatement({
        actions: ['iot:GetThingShadow', 'iot:ListNamedShadowsForThing'],
        resources: [
          this.formatArn({
            service: 'iot',
            resource: 'thing',
            resourceName: '*/*',
          }),
          iot('thing'),
        ],
      }),
    ]) {
      this.provisioningRole.addToPolicy(statement);
    }

    this.provisioner = new User(this, 'Provisioner', {
      userName: PROVISIONER_USER_NAME,
      managedPolicies: [
        ManagedPolicy.fromAwsManagedPolicyName(CLI_SIGN_IN_POLICY),
      ],
    });
    // The provisioner can also act as the operator (OperatorStack). The ARN
    // is built from the name to avoid a reference between nested stacks.
    this.provisioner.addToPolicy(
      new PolicyStatement({
        actions: ['sts:AssumeRole'],
        resources: [
          this.provisioningRole.roleArn,
          this.formatArn({
            service: 'iam',
            region: '',
            resource: 'role',
            resourceName: OPERATOR_ROLE_NAME,
          }),
        ],
      }),
    );
  }
}
