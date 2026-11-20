/**
 * @fileoverview The CDK app entry point (cdk.json runs it with tsx).
 *
 * Helios uses the same AWS account and region as the existing Kenley stack, in
 * its own stack (LLD 2, section 8).
 */

import {App} from 'aws-cdk-lib';

import {HeliosCloudStack} from '../lib/helios_cloud_stack';

const ACCOUNT = '857247606239';
const REGION = 'us-west-2';

const app = new App();
new HeliosCloudStack(app, 'HeliosCloud', {
  env: {account: ACCOUNT, region: REGION},
});
