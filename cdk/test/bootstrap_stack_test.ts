import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  BOOTSTRAP_STAGE,
  DEVICE_API_HANDLER,
  THROTTLE_BURST,
  THROTTLE_RATE_PER_SECOND,
} from '../lib/bootstrap_stack';
import {DEVICE_ROLE_ALIAS_NAME} from '../lib/device_identity_stack';
import {IOT_ENDPOINTS, testStack} from './test_stack';

function synthesize(): Template {
  return Template.fromStack(testStack().bootstrap);
}

/** The logical ID of the only DynamoDB table keyed by `key`. */
function tableKeyedBy(template: Template, key: string): string {
  const ids = Object.entries(
    template.findResources('AWS::DynamoDB::GlobalTable', {
      Properties: {KeySchema: [{AttributeName: key, KeyType: 'HASH'}]},
    }),
  ).map(([id]) => id);
  expect(ids).toHaveLength(1);
  return ids[0];
}

describe('BootstrapStack', () => {
  it('keeps device records and the MAC index, with point-in-time recovery', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::DynamoDB::GlobalTable', 3);
    for (const key of ['deviceId', 'mac']) {
      const id = tableKeyedBy(template, key);
      const table = template.toJSON()['Resources'][id];
      expect(table['DeletionPolicy']).toBe('Retain');
      expect(table['UpdateReplacePolicy']).toBe('Retain');
      expect(
        table['Properties']['Replicas'][0]['PointInTimeRecoverySpecification'],
      ).toEqual({PointInTimeRecoveryEnabled: true});
    }
  });

  it('indexes devices by thing name', () => {
    synthesize().hasResourceProperties('AWS::DynamoDB::GlobalTable', {
      KeySchema: [{AttributeName: 'deviceId', KeyType: 'HASH'}],
      GlobalSecondaryIndexes: [
        {
          IndexName: 'thingName',
          KeySchema: [{AttributeName: 'thingName', KeyType: 'HASH'}],
          Projection: {ProjectionType: 'KEYS_ONLY'},
        },
      ],
    });
  });

  it('expires challenges with TTL', () => {
    synthesize().hasResourceProperties('AWS::DynamoDB::GlobalTable', {
      KeySchema: [{AttributeName: 'challengeId', KeyType: 'HASH'}],
      TimeToLiveSpecification: {AttributeName: 'expiresAt', Enabled: true},
    });
  });

  it('runs the device API on java25 on ARM64', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::Lambda::Function', {
      Runtime: 'java25',
      Architectures: ['arm64'],
      Handler: DEVICE_API_HANDLER,
      MemorySize: 1024,
      Timeout: 10,
      Environment: {
        Variables: {
          DEVICES_TABLE: {Ref: tableKeyedBy(template, 'deviceId')},
          MAC_INDEX_TABLE: {Ref: tableKeyedBy(template, 'mac')},
          CHALLENGES_TABLE: {Ref: tableKeyedBy(template, 'challengeId')},
          DEFAULT_IOT_ENDPOINT: IOT_ENDPOINTS.data,
          DEFAULT_CREDENTIALS_ENDPOINT: IOT_ENDPOINTS.credentials,
          DEFAULT_ROLE_ALIAS: DEVICE_ROLE_ALIAS_NAME,
        },
      },
    });
  });

  it('keeps the audit log for 30 days', () => {
    synthesize().hasResourceProperties('AWS::Logs::LogGroup', {
      RetentionInDays: 30,
    });
  });

  it('gives the function only the DynamoDB calls it makes', () => {
    const template = synthesize();
    const devices = tableKeyedBy(template, 'deviceId');
    const policies = Object.values(template.findResources('AWS::IAM::Policy'));
    expect(policies).toHaveLength(1);

    expect(policies[0]['Properties']['PolicyDocument']['Statement']).toEqual([
      {
        Action: 'dynamodb:BatchGetItem',
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [tableKeyedBy(template, 'mac'), 'Arn']},
      },
      {
        Action: 'dynamodb:GetItem',
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [devices, 'Arn']},
      },
      {
        Action: 'dynamodb:UpdateItem',
        Condition: {
          'ForAllValues:StringEquals': {
            'dynamodb:Attributes': ['deviceId', 'lastSeen'],
          },
        },
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [devices, 'Arn']},
      },
      {
        Action: ['dynamodb:PutItem', 'dynamodb:UpdateItem'],
        Effect: 'Allow',
        Resource: {
          'Fn::GetAtt': [tableKeyedBy(template, 'challengeId'), 'Arn'],
        },
      },
    ]);
  });

  it('exposes only the two device routes, without an authorizer', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::ApiGateway::Method', 2);
    template.allResourcesProperties('AWS::ApiGateway::Method', {
      HttpMethod: 'POST',
      AuthorizationType: 'NONE',
      Integration: {Type: 'AWS_PROXY'},
    });
    const paths = Object.values(
      template.findResources('AWS::ApiGateway::Resource'),
    ).map(resource => resource['Properties']['PathPart']);
    expect(paths.sort()).toEqual(['bootstrap', 'challenge', 'device', 'v1']);
  });

  it('throttles the stage', () => {
    synthesize().hasResourceProperties('AWS::ApiGateway::Stage', {
      StageName: BOOTSTRAP_STAGE,
      MethodSettings: [
        Match.objectLike({
          HttpMethod: '*',
          ResourcePath: '/*',
          ThrottlingRateLimit: THROTTLE_RATE_PER_SECOND,
          ThrottlingBurstLimit: THROTTLE_BURST,
        }),
      ],
    });
  });

  it("doesn't change the account-wide API Gateway settings", () => {
    const template = synthesize();
    template.resourceCountIs('AWS::ApiGateway::Account', 0);
    template.resourceCountIs('AWS::IAM::Role', 1);
  });
});
