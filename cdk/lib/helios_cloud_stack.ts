/**
 * @fileoverview The CloudFormation stack for the Helios cloud resources.
 *
 * Empty for now. The device identity resources (AWS IoT policy, IAM role and
 * role alias; LLD 2, section 8) and the bootstrap service (Bootstrap API,
 * section 7) are added in later changes.
 */

import {Stack, Validations} from 'aws-cdk-lib';
import type {StackProps} from 'aws-cdk-lib';
import type {Construct} from 'constructs';

/** The stack that holds all Helios cloud resources. */
export class HeliosCloudStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, {
      description: 'Helios cloud resources (github.com/kench/helios-cloud)',
      ...props,
    });

    // Remove this once the stack has its first resource.
    Validations.of(this).acknowledge({
      id: 'CloudFormation-Validate::F0001',
      reason: 'The stack is empty until the first Helios resources are added',
    });
  }
}
