/**
 * @fileoverview The CloudFormation stack for the Helios cloud resources.
 *
 * Each group of resources is a nested stack. The bootstrap service (Bootstrap
 * API, section 7) is added in a later change.
 */

import {Stack} from 'aws-cdk-lib';
import type {StackProps} from 'aws-cdk-lib';
import type {Construct} from 'constructs';

import {DeviceIdentityStack} from './device_identity_stack';

/** The stack that holds all Helios cloud resources. */
export class HeliosCloudStack extends Stack {
  /** The device IoT policy, IAM role and role alias. */
  readonly deviceIdentity: DeviceIdentityStack;

  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, {
      description: 'Helios cloud resources (github.com/kench/helios-cloud)',
      ...props,
    });

    this.deviceIdentity = new DeviceIdentityStack(this, 'DeviceIdentity');
  }
}
