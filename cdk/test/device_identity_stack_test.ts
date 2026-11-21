import {App} from 'aws-cdk-lib';
import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  DEVICE_POLICY_NAME,
  DEVICE_ROLE_ALIAS_NAME,
} from '../lib/device_identity_stack';
import {HeliosCloudStack} from '../lib/helios_cloud_stack';

const ACCOUNT = '123456789012';
const REGION = 'us-west-2';
const IOT = `arn:aws:iot:${REGION}:${ACCOUNT}`;
const THING = '${iot:Connection.Thing.ThingName}';
const SHADOWS = `$aws/things/${THING}/shadow/name/*`;

function synthesize(): Template {
  const stack = new HeliosCloudStack(new App(), 'TestStack', {
    env: {account: ACCOUNT, region: REGION},
  });
  return Template.fromStack(stack.deviceIdentity);
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

  it('lets devices read only their own SRT secrets', () => {
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
        ],
      },
      {
        Action: 'iot:Subscribe',
        Effect: 'Allow',
        Resource: [
          `${IOT}:topicfilter/helios/${THING}/cmd/*`,
          `${IOT}:topicfilter/${SHADOWS}/get/*`,
          `${IOT}:topicfilter/${SHADOWS}/update/*`,
        ],
      },
      {
        Action: 'iot:Receive',
        Effect: 'Allow',
        Resource: [
          `${IOT}:topic/helios/${THING}/cmd/*`,
          `${IOT}:topic/${SHADOWS}/get/*`,
          `${IOT}:topic/${SHADOWS}/update/*`,
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
