/**
 * @fileoverview The CloudFormation stack for the Helios cloud resources.
 *
 * Each group of resources is a nested stack: device identity, provisioning,
 * and the bootstrap service (Bootstrap API, section 7).
 */

import {CfnOutput, Stack} from 'aws-cdk-lib';
import type {StackProps} from 'aws-cdk-lib';
import type {Code} from 'aws-cdk-lib/aws-lambda';
import type {Construct} from 'constructs';

import {BootstrapStack} from './bootstrap_stack';
import {
  DEVICE_ROLE_ALIAS_NAME,
  DeviceIdentityStack,
} from './device_identity_stack';
import {ProvisioningStack} from './provisioning_stack';

/** The account's AWS IoT endpoints, which devices are told to use. */
export interface IotEndpoints {
  /** `aws iot describe-endpoint --endpoint-type iot:Data-ATS`. */
  readonly data: string;
  /** `aws iot describe-endpoint --endpoint-type iot:CredentialProvider`. */
  readonly credentials: string;
}

/** Settings for {@link HeliosCloudStack}. */
export interface HeliosCloudStackProps extends StackProps {
  /** The device API Lambda's deployment package. */
  readonly deviceApiCode: Code;
  /** The account's AWS IoT endpoints. */
  readonly iotEndpoints: IotEndpoints;
}

/** The stack that holds all Helios cloud resources. */
export class HeliosCloudStack extends Stack {
  /** The device IoT policy, IAM role and role alias. */
  readonly deviceIdentity: DeviceIdentityStack;

  /** The role for provisioning devices. */
  readonly provisioning: ProvisioningStack;

  /** The bootstrap service. */
  readonly bootstrap: BootstrapStack;

  constructor(scope: Construct, id: string, props: HeliosCloudStackProps) {
    super(scope, id, {
      description: 'Helios cloud resources (github.com/kench/helios-cloud)',
      ...props,
    });

    this.deviceIdentity = new DeviceIdentityStack(this, 'DeviceIdentity');
    this.provisioning = new ProvisioningStack(this, 'Provisioning');
    this.bootstrap = new BootstrapStack(this, 'Bootstrap', {
      deviceApiCode: props.deviceApiCode,
      defaultAws: {
        iotEndpoint: props.iotEndpoints.data,
        credentialsEndpoint: props.iotEndpoints.credentials,
        roleAlias: DEVICE_ROLE_ALIAS_NAME,
      },
    });

    new CfnOutput(this, 'BootstrapUrl', {
      description: "Helios's bootstrap.url (the default execute-api domain)",
      value: this.bootstrap.bootstrapUrl,
    });
  }
}
