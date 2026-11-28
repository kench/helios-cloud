import {readFileSync} from 'node:fs';
import {join} from 'node:path';

import {App} from 'aws-cdk-lib';
import {Code} from 'aws-cdk-lib/aws-lambda';
import {Source} from 'aws-cdk-lib/aws-s3-deployment';

import {HeliosCloudStack} from '../lib/helios_cloud_stack';

/** The account and region the tests synthesize for. */
export const ACCOUNT = '123456789012';
export const REGION = 'us-west-2';

/** The endpoints the tests give the stack. */
export const IOT_ENDPOINTS = {
  data: 'example-ats.iot.us-west-2.amazonaws.com',
  credentials: 'example.credentials.iot.us-west-2.amazonaws.com',
};

/** The identity provider issuer the tests give the stack. */
export const ISSUER =
  'https://issuer-cognito-idp.us-west-2.amazonaws.com/us-west-2_example';

/** The identity provider the tests give the stack. */
export const IDENTITY_PROVIDER = {
  issuer: ISSUER,
  domain: 'https://example.auth.us-west-2.amazoncognito.com',
  consoleClientId: 'example-client',
};

/** A stand-in for the console's build/client. */
export const CONSOLE_FIXTURE = join(__dirname, 'fixtures', 'console');

/** A HeliosCloud stack with placeholder Lambda and console assets. */
export function testStack(): HeliosCloudStack {
  return new HeliosCloudStack(new App(), 'TestStack', {
    env: {account: ACCOUNT, region: REGION},
    deviceApiCode: Code.fromAsset(join(__dirname, 'fixtures', 'lambda')),
    adminApiCode: Code.fromAsset(join(__dirname, 'fixtures', 'lambda')),
    controlApiCode: Code.fromAsset(join(__dirname, 'fixtures', 'lambda')),
    iotEndpoints: IOT_ENDPOINTS,
    identityProvider: IDENTITY_PROVIDER,
    console: {
      site: Source.asset(CONSOLE_FIXTURE),
      indexHtml: readFileSync(join(CONSOLE_FIXTURE, 'index.html'), 'utf8'),
    },
  });
}

/**
 * Replaces the `Fn::Join` that `formatArn` produces with the ARN it builds in
 * the `aws` partition, so that statements can be compared as plain strings.
 */
export function resolveArns(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(resolveArns);
  }
  if (value === null || typeof value !== 'object') {
    return value;
  }
  const join = 'Fn::Join' in value ? value['Fn::Join'] : undefined;
  if (Array.isArray(join) && join[0] === '') {
    const parts: unknown[] = join[1];
    return parts
      .map(part =>
        typeof part === 'string'
          ? part
          : JSON.stringify(part) === '{"Ref":"AWS::Partition"}'
            ? 'aws'
            : JSON.stringify(part),
      )
      .join('');
  }
  return Object.fromEntries(
    Object.entries(value).map(([key, child]) => [key, resolveArns(child)]),
  );
}
