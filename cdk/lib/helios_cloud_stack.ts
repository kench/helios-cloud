/**
 * @fileoverview The CloudFormation stack for the Helios cloud resources.
 *
 * Each group of resources is a nested stack: device identity, provisioning,
 * operating devices, the bootstrap service (Bootstrap API, section 7), the
 * APT repository for Helios releases, and the console (the Control API's web
 * UI).
 */

import {CfnOutput, Stack} from 'aws-cdk-lib';
import type {StackProps} from 'aws-cdk-lib';
import type {Code} from 'aws-cdk-lib/aws-lambda';
import type {ISource} from 'aws-cdk-lib/aws-s3-deployment';
import type {Construct} from 'constructs';

import {AptRepositoryStack} from './apt_repository_stack';
import {BootstrapStack} from './bootstrap_stack';
import {ConsoleConfigStack} from './console_config_stack';
import {CONSOLE_DEV_ORIGIN, ConsoleStack} from './console_stack';
import {HELIOS_CONTROL_AUDIENCE, controlScope} from './control_token_api';
import type {ConsoleDomain} from './console_stack';
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

/** The identity provider's values, from kench/identity-provider's outputs. */
export interface IdentityProvider {
  /** `Issuer`: the `iss` of its tokens. */
  readonly issuer: string;
  /** `CognitoDomain`: where its managed login and OAuth endpoints are. */
  readonly domain: string;
  /** `HeliosConsoleClientId`: the console's app client (public). */
  readonly consoleClientId: string;
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
  /** The identity provider the console signs in with (kench/identity-provider). */
  readonly identityProvider: IdentityProvider;
  /** The console's built site (helios-console's `build/client`). */
  readonly console: {
    readonly site: ISource;
    /** Its `index.html`, for the content security policy. */
    readonly indexHtml: string;
    /** A custom domain; CloudFront's default domain only, if absent. */
    readonly domain?: ConsoleDomain;
  };
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

  /** The console, the Control API's web UI. */
  readonly console: ConsoleStack;

  /** The console's config.json: the API and sign-in. */
  readonly consoleConfig: ConsoleConfigStack;

  constructor(scope: Construct, id: string, props: HeliosCloudStackProps) {
    super(scope, id, {
      description: 'Helios cloud resources (github.com/kench/helios-cloud)',
      ...props,
    });

    this.deviceIdentity = new DeviceIdentityStack(this, 'DeviceIdentity');
    this.provisioning = new ProvisioningStack(this, 'Provisioning');
    this.operator = new OperatorStack(this, 'Operator');
    // Before the bootstrap stack: the Control API for browsers lets the
    // console's origin call it.
    this.console = new ConsoleStack(this, 'Console', {
      ...props.console,
      // The Control API for browsers can't be named exactly here (it
      // depends on this stack, for CORS), so any API Gateway in the region:
      // a custom domain will narrow it.
      connectTo: [
        props.identityProvider.domain,
        `https://*.execute-api.${this.region}.amazonaws.com`,
      ],
    });
    this.bootstrap = new BootstrapStack(this, 'Bootstrap', {
      deviceApiCode: props.deviceApiCode,
      adminApiCode: props.adminApiCode,
      controlApiCode: props.controlApiCode,
      defaultAws: {
        iotEndpoint: props.iotEndpoints.data,
        credentialsEndpoint: props.iotEndpoints.credentials,
        roleAlias: DEVICE_ROLE_ALIAS_NAME,
      },
      controlTokens: {
        issuer: props.identityProvider.issuer,
        allowedOrigins: [this.console.origin, CONSOLE_DEV_ORIGIN],
      },
    });

    this.aptRepository = new AptRepositoryStack(this, 'AptRepository');
    this.consoleConfig = new ConsoleConfigStack(this, 'ConsoleConfig', {
      bucket: this.console.bucket,
      distribution: this.console.distribution,
      config: {
        apiUrl: this.bootstrap.controlTokenApi.url,
        auth: {
          domain: props.identityProvider.domain,
          clientId: props.identityProvider.consoleClientId,
          resource: HELIOS_CONTROL_AUDIENCE,
          scopes: ['openid', controlScope('read'), controlScope('write')],
        },
      },
    });

    // Provisioning registers each device it creates.
    this.bootstrap.grantProvisioning(this.provisioning.provisioningRole);

    // Operators control devices' streams through the control API.
    this.bootstrap.grantControl(this.operator.operatorRole);
    this.bootstrap.grantEgress(this.deviceIdentity.deviceRole);

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
    new CfnOutput(this, 'ControlTokenApiUrl', {
      description:
        'The Control API for browsers (identity provider access tokens)',
      value: this.bootstrap.controlTokenApi.url,
    });
    new CfnOutput(this, 'ConsoleUrl', {
      description: "The console, at CloudFront's default domain",
      value: this.console.url,
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
