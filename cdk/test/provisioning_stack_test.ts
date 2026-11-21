import {App} from 'aws-cdk-lib';
import {Match, Template} from 'aws-cdk-lib/assertions';

import {HeliosCloudStack} from '../lib/helios_cloud_stack';
import {
  PROVISIONER_USER_NAME,
  PROVISIONING_ROLE_NAME,
} from '../lib/provisioning_stack';

const ACCOUNT = '123456789012';
const REGION = 'us-west-2';
const IOT = `arn:aws:iot:${REGION}:${ACCOUNT}`;

function synthesize(): Template {
  const stack = new HeliosCloudStack(new App(), 'TestStack', {
    env: {account: ACCOUNT, region: REGION},
  });
  return Template.fromStack(stack.provisioning);
}

/**
 * Replaces the `Fn::Join` that `formatArn` produces with the ARN it builds in
 * the `aws` partition, so that statements can be compared as plain strings.
 */
function resolveArns(value: unknown): unknown {
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

/** The only `AWS::IAM::Policy` attached to the only resource of `type`. */
function policyFor(template: Template, type: string) {
  const owners = Object.keys(template.findResources(type));
  expect(owners).toHaveLength(1);
  const property = type === 'AWS::IAM::Role' ? 'Roles' : 'Users';
  const policies = Object.values(
    template.findResources('AWS::IAM::Policy', {
      Properties: {[property]: [{Ref: owners[0]}]},
    }),
  );
  expect(policies).toHaveLength(1);
  return policies[0];
}

describe('ProvisioningStack', () => {
  it('names the role and lets only this account assume it', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::IAM::Role', 1);
    const [role] = Object.values(template.findResources('AWS::IAM::Role'));
    expect(role['Properties']['RoleName']).toBe(PROVISIONING_ROLE_NAME);
    expect(
      resolveArns(role['Properties']['AssumeRolePolicyDocument']['Statement']),
    ).toEqual([
      {
        Action: 'sts:AssumeRole',
        Effect: 'Allow',
        Principal: {AWS: `arn:aws:iam::${ACCOUNT}:root`},
      },
    ]);
  });

  it('allows only creating and attaching per-device IoT resources', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::IAM::Role', {
      ManagedPolicyArns: Match.absent(),
      Policies: Match.absent(),
    });
    const policy = policyFor(template, 'AWS::IAM::Role');

    expect(
      resolveArns(policy['Properties']['PolicyDocument']['Statement']),
    ).toEqual([
      {
        Action: 'iot:CreateThing',
        Effect: 'Allow',
        Resource: `${IOT}:thing/*`,
      },
      {
        Action: ['iot:CreateCertificateFromCsr', 'iot:DescribeEndpoint'],
        Effect: 'Allow',
        Resource: '*',
      },
      {
        Action: ['iot:AttachThingPrincipal', 'iot:AttachPolicy'],
        Effect: 'Allow',
        Resource: `${IOT}:cert/*`,
      },
    ]);
  });

  it('has a user that can only assume the role and sign in to the CLI', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::IAM::User', 1);
    template.resourceCountIs('AWS::IAM::AccessKey', 0);
    const [user] = Object.values(template.findResources('AWS::IAM::User'));
    expect(user['Properties']['UserName']).toBe(PROVISIONER_USER_NAME);
    // No password from CDK: it's set in the console with MFA.
    expect(user['Properties']['LoginProfile']).toBeUndefined();
    expect(user['Properties']['Policies']).toBeUndefined();
    expect(resolveArns(user['Properties']['ManagedPolicyArns'])).toEqual([
      'arn:aws:iam::aws:policy/SignInLocalDevelopmentAccess',
    ]);
    const roles = Object.keys(template.findResources('AWS::IAM::Role'));

    const policy = policyFor(template, 'AWS::IAM::User');

    expect(policy['Properties']['PolicyDocument']['Statement']).toEqual([
      {
        Action: 'sts:AssumeRole',
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [roles[0], 'Arn']},
      },
    ]);
  });
});
