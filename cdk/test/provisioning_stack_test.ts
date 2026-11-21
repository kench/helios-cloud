import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  PROVISIONER_USER_NAME,
  PROVISIONING_ROLE_NAME,
} from '../lib/provisioning_stack';

import {ACCOUNT, REGION, resolveArns, testStack} from './test_stack';

const IOT = `arn:aws:iot:${REGION}:${ACCOUNT}`;

/** An execute-api ARN for a route on the bootstrap API's dev stage. */
function apiRoute(method: string, path: string): RegExp {
  return new RegExp(
    `^arn:aws:execute-api:${REGION}:${ACCOUNT}:.*Bootstrap.*Api.*/dev/${method}${path}$`,
  );
}

function synthesize(): Template {
  return Template.fromStack(testStack().provisioning);
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
      // RegisterDevice and GetDevice on the bootstrap admin API, and nothing
      // else there (BootstrapStack.grantProvisioning).
      {
        Action: 'execute-api:Invoke',
        Effect: 'Allow',
        Resource: [
          expect.stringMatching(apiRoute('POST', '/v1/admin/devices')),
          expect.stringMatching(apiRoute('GET', '/v1/admin/devices/\\*')),
        ],
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
