import {join} from 'node:path';

import {App} from 'aws-cdk-lib';
import {Code} from 'aws-cdk-lib/aws-lambda';

import {HeliosCloudStack} from '../lib/helios_cloud_stack';

/** The account and region the tests synthesize for. */
export const ACCOUNT = '123456789012';
export const REGION = 'us-west-2';

/** The endpoints the tests give the stack. */
export const IOT_ENDPOINTS = {
  data: 'example-ats.iot.us-west-2.amazonaws.com',
  credentials: 'example.credentials.iot.us-west-2.amazonaws.com',
};

/** A HeliosCloud stack with a placeholder Lambda asset. */
export function testStack(): HeliosCloudStack {
  return new HeliosCloudStack(new App(), 'TestStack', {
    env: {account: ACCOUNT, region: REGION},
    deviceApiCode: Code.fromAsset(join(__dirname, 'fixtures', 'lambda')),
    adminApiCode: Code.fromAsset(join(__dirname, 'fixtures', 'lambda')),
    controlApiCode: Code.fromAsset(join(__dirname, 'fixtures', 'lambda')),
    iotEndpoints: IOT_ENDPOINTS,
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
