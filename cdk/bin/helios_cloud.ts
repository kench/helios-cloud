/**
 * @fileoverview The CDK app entry point (cdk.json runs it with tsx).
 *
 * Helios uses the same AWS account and region as the existing Kenley stack, in
 * its own stack (LLD 2, section 8).
 */

import {existsSync, readFileSync} from 'node:fs';
import {join} from 'node:path';

import {App} from 'aws-cdk-lib';
import {Code} from 'aws-cdk-lib/aws-lambda';
import {Source} from 'aws-cdk-lib/aws-s3-deployment';

import {HeliosCloudStack} from '../lib/helios_cloud_stack';

const ACCOUNT = '857247606239';
const REGION = 'us-west-2';

/** The account's AWS IoT endpoints (`aws iot describe-endpoint`). */
const IOT_ENDPOINTS = {
  data: 'a2p9gwnksxjg9z-ats.iot.us-west-2.amazonaws.com',
  credentials: 'c1u80y5c766riu.credentials.iot.us-west-2.amazonaws.com',
};

/** A Lambda package built by `./gradlew lambdaZip` in ../lambda. */
function lambdaPackage(project: string): Code {
  const zip = join(
    __dirname,
    '..',
    '..',
    'lambda',
    project,
    'build',
    'lambda',
    `${project}.zip`,
  );
  if (!existsSync(zip)) {
    throw new Error(
      `${zip} not found: run ./gradlew lambdaZip in lambda/ first`,
    );
  }
  return Code.fromAsset(zip);
}

/**
 * The console as built by `npm ci && npm run build` in ../console (the
 * helios-console submodule).
 */
function consoleSite() {
  const dir = join(__dirname, '..', '..', 'console', 'build', 'client');
  const index = join(dir, 'index.html');
  if (!existsSync(index)) {
    throw new Error(
      `${index} not found: run npm ci && npm run build in console/ first`,
    );
  }
  return {site: Source.asset(dir), indexHtml: readFileSync(index, 'utf8')};
}

/**
 * The identity provider's issuer (kench/identity-provider, output `Issuer`):
 * the Control API for browsers accepts its access tokens.
 */
const IDENTITY_PROVIDER_ISSUER =
  'https://issuer-cognito-idp.us-west-2.amazonaws.com/us-west-2_CldV7cnq6';

const app = new App();
new HeliosCloudStack(app, 'HeliosCloud', {
  env: {account: ACCOUNT, region: REGION},
  deviceApiCode: lambdaPackage('device-api'),
  adminApiCode: lambdaPackage('admin-api'),
  controlApiCode: lambdaPackage('control-api'),
  iotEndpoints: IOT_ENDPOINTS,
  identityProviderIssuer: IDENTITY_PROVIDER_ISSUER,
  console: consoleSite(),
});
