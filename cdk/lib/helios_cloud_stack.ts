/**
 * @fileoverview The CloudFormation stack for the Helios cloud resources.
 *
 * Each group of resources is a nested stack: device identity, provisioning,
 * operating devices, the bootstrap service (Bootstrap API, section 7), and the
 * APT repository for Helios releases.
 */

import {CfnOutput, Stack} from 'aws-cdk-lib';
import type {StackProps} from 'aws-cdk-lib';
import type {Code} from 'aws-cdk-lib/aws-lambda';
import type {Construct} from 'constructs';

import {AptRepositoryStack} from './apt_repository_stack';
import {BootstrapStack} from './bootstrap_stack';
import {
  DEVICE_ROLE_ALIAS_NAME,
  DeviceIdentityStack,
} from './device_identity_stack';
import {OperatorStack} from './operator_stack';
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
  /** The admin API Lambda's deployment package. */
  readonly adminApiCode: Code;
  /** The control API Lambda's deployment package. */
  readonly controlApiCode: Code;
  /** The account's AWS IoT endpoints. */
  readonly iotEndpoints: IotEndpoints;
}

/** The stack that holds all Helios cloud resources. */
export class HeliosCloudStack extends Stack {
  /** The device IoT policy, IAM role and role alias. */
  readonly deviceIdentity: DeviceIdentityStack;

  /** The role for provisioning devices. */
  readonly provisioning: ProvisioningStack;

  /** The role for sending device commands. */
  readonly operator: OperatorStack;

  /** The bootstrap service. */
  readonly bootstrap: BootstrapStack;

  /** The APT repository for Helios releases. */
  readonly aptRepository: AptRepositoryStack;

  constructor(scope: Construct, id: string, props: HeliosCloudStackProps) {
    super(scope, id, {
      description: 'Helios cloud resources (github.com/kench/helios-cloud)',
      ...props,
    });

    this.deviceIdentity = new DeviceIdentityStack(this, 'DeviceIdentity');
    this.provisioning = new ProvisioningStack(this, 'Provisioning');
    this.operator = new OperatorStack(this, 'Operator');
    this.bootstrap = new BootstrapStack(this, 'Bootstrap', {
      deviceApiCode: props.deviceApiCode,
      adminApiCode: props.adminApiCode,
      controlApiCode: props.controlApiCode,
      defaultAws: {
        iotEndpoint: props.iotEndpoints.data,
        credentialsEndpoint: props.iotEndpoints.credentials,
        roleAlias: DEVICE_ROLE_ALIAS_NAME,
      },
    });

    this.aptRepository = new AptRepositoryStack(this, 'AptRepository');

    // Provisioning registers each device it creates.
    this.bootstrap.grantProvisioning(this.provisioning.provisioningRole);

    // Operators control devices' streams through the control API.
    this.bootstrap.grantControl(this.operator.operatorRole);

    // TEMPORARY: the provisioning role can also send commands, so that a
    // freshly provisioned scanner can be checked end to end (helios.ping)
    // with the one profile we sign in with today. Commands belong to
    // helios-operator; remove this grant once the provisioner user (or
    // whoever operates devices) assumes that role instead.
    this.operator.grantCommands(this.provisioning.provisioningRole);

    new CfnOutput(this, 'BootstrapUrl', {
      description: "Helios's bootstrap.url (the default execute-api domain)",
      value: this.bootstrap.bootstrapUrl,
    });
    new CfnOutput(this, 'AptRepositoryUrl', {
      description: "The APT repository, for devices' sources",
      value: this.aptRepository.repositoryUrl,
    });
    new CfnOutput(this, 'AptBucketName', {
      description: 'The APT repository bucket, for the release workflow',
      value: this.aptRepository.bucket.bucketName,
    });
    new CfnOutput(this, 'AptDistributionId', {
      description: 'The APT repository distribution, for invalidations',
      value: this.aptRepository.distribution.distributionId,
    });
    new CfnOutput(this, 'AptPublisherRoleArn', {
      description: 'The role the helios release workflow assumes',
      value: this.aptRepository.publisherRole.roleArn,
    });
    new CfnOutput(this, 'AptPackagesPublisherRoleArn', {
      description: 'The role the helios-packages release workflow assumes',
      value: this.aptRepository.packagesPublisherRole.roleArn,
    });
  }
}
