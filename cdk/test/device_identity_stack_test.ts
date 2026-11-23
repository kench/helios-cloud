import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  DEVICE_POLICY_NAME,
  DEVICE_ROLE_ALIAS_NAME,
} from '../lib/device_identity_stack';

import {ACCOUNT, REGION, resolveArns, testStack} from './test_stack';

const IOT = `arn:aws:iot:${REGION}:${ACCOUNT}`;
const THING = '${iot:Connection.Thing.ThingName}';
const SHADOWS = `$aws/things/${THING}/shadow/name/*`;
const EXECUTIONS = `$aws/commands/things/${THING}/executions`;

function synthesize(): Template {
  return Template.fromStack(testStack().deviceIdentity);
}

/** Returns the statements of the only resource of `type` in the template. */
function statements(
  template: Template,
  type: string,
  documentProperty: string,
): unknown {
  const resources = Object.values(template.findResources(type));
  expect(resources).toHaveLength(1);
  const document = resources[0]['Properties'][documentProperty];
  return resolveArns(document['Statement']);
}

describe('DeviceIdentityStack', () => {
  it('lets the credentials provider assume the device role', () => {
    synthesize().hasResourceProperties('AWS::IAM::Role', {
      AssumeRolePolicyDocument: {
        Statement: [
          {
            Action: 'sts:AssumeRole',
            Effect: 'Allow',
            Principal: {Service: 'credentials.iot.amazonaws.com'},
          },
        ],
      },
    });
  });

  it('lets devices read only their own SRT secrets and call only the egress echo', () => {
    expect(
      statements(synthesize(), 'AWS::IAM::Policy', 'PolicyDocument'),
    ).toEqual([
      {
        Action: 'secretsmanager:GetSecretValue',
        Effect: 'Allow',
        Resource:
          `arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:` +
          'helios/srt/${credentials-iot:ThingName}*',
      },
      {
        Action: 'execute-api:Invoke',
        Effect: 'Allow',
        // The bootstrap API's ID is a reference; only this one route.
        Resource: expect.stringMatching(
          new RegExp(
            `^arn:aws:execute-api:${REGION}:${ACCOUNT}:.+/dev/GET/v1/device/egress$`,
          ),
        ),
      },
    ]);
  });

  it('gives the device role no other policies', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::IAM::Policy', 1);
    template.hasResourceProperties('AWS::IAM::Role', {
      ManagedPolicyArns: Match.absent(),
      Policies: Match.absent(),
    });
  });

  it('creates the role alias for the device role', () => {
    const template = synthesize();
    const roles = Object.keys(template.findResources('AWS::IAM::Role'));
    expect(roles).toHaveLength(1);
    template.hasResourceProperties('AWS::IoT::RoleAlias', {
      RoleAlias: DEVICE_ROLE_ALIAS_NAME,
      RoleArn: {'Fn::GetAtt': [roles[0], 'Arn']},
      CredentialDurationSeconds: Match.absent(),
    });
  });

  it('names the device policy for the provisioning script', () => {
    synthesize().hasResourceProperties('AWS::IoT::Policy', {
      PolicyName: DEVICE_POLICY_NAME,
    });
  });

  it("keeps the device policy within AWS IoT's 2,048-character limit", () => {
    // AWS IoT refuses a policy over 2,048 characters, not counting whitespace
    // (helios-cloud #17's first deploy failed on it). Measured with the ARNs
    // as they deploy, in a 12-digit account.
    const document = statements(
      synthesize(),
      'AWS::IoT::Policy',
      'PolicyDocument',
    );
    const deployed = JSON.stringify({
      Version: '2012-10-17',
      Statement: document,
    })
      .replaceAll(ACCOUNT, '123456789012')
      .replace(/\s/g, '');
    expect(deployed.length).toBeLessThanOrEqual(2048);
  });

  it('limits each device to its own client ID, topics and shadows', () => {
    const template = synthesize();
    const alias = Object.keys(template.findResources('AWS::IoT::RoleAlias'));
    expect(alias).toHaveLength(1);
    expect(statements(template, 'AWS::IoT::Policy', 'PolicyDocument')).toEqual([
      {
        Action: 'iot:Connect',
        Effect: 'Allow',
        Resource: `${IOT}:client/${THING}`,
        Condition: {Bool: {'iot:Connection.Thing.IsAttached': 'true'}},
      },
      {
        Action: 'iot:Publish',
        Effect: 'Allow',
        Resource: [
          `${IOT}:topic/helios/${THING}/rsp/*`,
          `${IOT}:topic/helios/${THING}/evt/*`,
          `${IOT}:topic/${SHADOWS}/get`,
          `${IOT}:topic/${SHADOWS}/update`,
          `${IOT}:topic/${EXECUTIONS}/*/response/json`,
        ],
      },
      {
        Action: 'iot:Subscribe',
        Effect: 'Allow',
        Resource: [
          `${IOT}:topicfilter/helios/${THING}/cmd/*`,
          `${IOT}:topicfilter/${SHADOWS}/get/*`,
          `${IOT}:topicfilter/${SHADOWS}/update/*`,
          `${IOT}:topicfilter/${EXECUTIONS}/*`,
        ],
      },
      {
        Action: 'iot:Receive',
        Effect: 'Allow',
        Resource: [
          `${IOT}:topic/helios/${THING}/cmd/*`,
          `${IOT}:topic/${SHADOWS}/get/*`,
          `${IOT}:topic/${SHADOWS}/update/*`,
          `${IOT}:topic/${EXECUTIONS}/*`,
        ],
      },
      {
        Action: 'iot:AssumeRoleWithCertificate',
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [alias[0], 'RoleAliasArn']},
      },
    ]);
  });
});
