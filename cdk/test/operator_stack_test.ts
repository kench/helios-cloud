import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  OPERATOR_CLIENT_PREFIX,
  OPERATOR_ROLE_NAME,
} from '../lib/operator_stack';

import {ACCOUNT, REGION, resolveArns, testStack} from './test_stack';

const IOT = `arn:aws:iot:${REGION}:${ACCOUNT}`;

function synthesize(): Template {
  return Template.fromStack(testStack().operator);
}

describe('OperatorStack', () => {
  it('names the role and lets only this account assume it', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::IAM::Role', 1);
    const [role] = Object.values(template.findResources('AWS::IAM::Role'));
    expect(role['Properties']['RoleName']).toBe(OPERATOR_ROLE_NAME);
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

  it('allows only sending commands and reading responses', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::IAM::Role', {
      ManagedPolicyArns: Match.absent(),
      Policies: Match.absent(),
    });
    const policies = Object.values(template.findResources('AWS::IAM::Policy'));
    expect(policies).toHaveLength(1);

    expect(
      resolveArns(policies[0]['Properties']['PolicyDocument']['Statement']),
    ).toEqual([
      {
        Action: 'iot:Connect',
        Effect: 'Allow',
        Resource: `${IOT}:client/${OPERATOR_CLIENT_PREFIX}*`,
      },
      {
        Action: 'iot:Publish',
        Effect: 'Allow',
        Resource: `${IOT}:topic/helios/*/cmd/*`,
      },
      {
        Action: 'iot:Subscribe',
        Effect: 'Allow',
        Resource: `${IOT}:topicfilter/helios/*/rsp/*`,
      },
      {
        Action: 'iot:Receive',
        Effect: 'Allow',
        Resource: `${IOT}:topic/helios/*/rsp/*`,
      },
    ]);
  });
});
