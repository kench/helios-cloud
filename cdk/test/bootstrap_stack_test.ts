import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  ADMIN_API_HANDLER,
  BOOTSTRAP_STAGE,
  CONTROL_API_HANDLER,
  DEVICE_API_HANDLER,
  THROTTLE_BURST,
  THROTTLE_RATE_PER_SECOND,
} from '../lib/bootstrap_stack';
import {DEVICE_ROLE_ALIAS_NAME} from '../lib/device_identity_stack';
import {
  ACCOUNT,
  IOT_ENDPOINTS,
  REGION,
  resolveArns,
  testStack,
} from './test_stack';

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

/** The statements of the policy on the function whose logical ID starts with `id`. */
function statementsOf(template: Template, id: string): unknown {
  const policies = Object.values(
    template.findResources('AWS::IAM::Policy'),
  ).filter(policy =>
    policy['Properties']['Roles'][0]['Ref'].startsWith(`${id}ServiceRole`),
  );
  expect(policies).toHaveLength(1);
  return policies[0]['Properties']['PolicyDocument']['Statement'];
}

describe('BootstrapStack', () => {
  it('keeps device records and the MAC index if the stack is deleted', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::DynamoDB::GlobalTable', 3);
    for (const key of ['deviceId', 'mac']) {
      const table = template.toJSON()['Resources'][tableKeyedBy(template, key)];
      expect(table['DeletionPolicy']).toBe('Retain');
      expect(table['UpdateReplacePolicy']).toBe('Retain');
    }
    const challenges =
      template.toJSON()['Resources'][tableKeyedBy(template, 'challengeId')];
    expect(challenges['DeletionPolicy']).toBe('Delete');
  });

  it('uses the Standard class, on-demand billing and no point-in-time recovery', () => {
    const tables = Object.values(
      synthesize().findResources('AWS::DynamoDB::GlobalTable'),
    );
    expect(tables).toHaveLength(3);
    for (const table of tables) {
      expect(table['Properties']['BillingMode']).toBe('PAY_PER_REQUEST');
      for (const replica of table['Properties']['Replicas']) {
        expect(replica['TableClass']).toBe('STANDARD');
        expect(replica['PointInTimeRecoverySpecification']).toBeUndefined();
      }
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

  it('gives the device API only the DynamoDB calls it makes', () => {
    const template = synthesize();
    const devices = tableKeyedBy(template, 'deviceId');

    expect(statementsOf(template, 'DeviceApi')).toEqual([
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

  it('gives the admin API only the DynamoDB calls it makes', () => {
    const template = synthesize();
    const devices = tableKeyedBy(template, 'deviceId');

    expect(statementsOf(template, 'AdminApi')).toEqual([
      {
        Action: [
          'dynamodb:GetItem',
          'dynamodb:PutItem',
          'dynamodb:UpdateItem',
          'dynamodb:DeleteItem',
        ],
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [devices, 'Arn']},
      },
      {
        Action: 'dynamodb:Query',
        Effect: 'Allow',
        Resource: {
          'Fn::Join': [
            '',
            [{'Fn::GetAtt': [devices, 'Arn']}, '/index/thingName'],
          ],
        },
      },
      {
        Action: ['dynamodb:PutItem', 'dynamodb:DeleteItem'],
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [tableKeyedBy(template, 'mac'), 'Arn']},
      },
    ]);
  });

  it('runs the admin API on java25 with its own 30-day log', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::Lambda::Function', {
      Runtime: 'java25',
      Architectures: ['arm64'],
      Handler: ADMIN_API_HANDLER,
      Environment: {
        Variables: {
          DEVICES_TABLE: {Ref: tableKeyedBy(template, 'deviceId')},
          MAC_INDEX_TABLE: {Ref: tableKeyedBy(template, 'mac')},
        },
      },
    });
    template.resourceCountIs('AWS::Logs::LogGroup', 3);
  });

  it('runs the control API on java25 with its own 30-day log', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::Lambda::Function', {
      Runtime: 'java25',
      Architectures: ['arm64'],
      Handler: CONTROL_API_HANDLER,
      Timeout: 15,
      Environment: {
        Variables: {
          DEVICES_TABLE: {Ref: tableKeyedBy(template, 'deviceId')},
          IOT_DATA_ENDPOINT: IOT_ENDPOINTS.data,
        },
      },
    });
    const control = Object.values(
      template.findResources('AWS::Lambda::Function', {
        Properties: {Handler: CONTROL_API_HANDLER},
      }),
    )[0];
    expect(
      resolveArns(
        control['Properties']['Environment']['Variables']['IOT_ARN_PREFIX'],
      ),
    ).toBe(`arn:aws:iot:${REGION}:${ACCOUNT}`);
  });

  it('lets the control API read devices and shadows, write only the stream shadow, and run only the Helios commands', () => {
    const template = synthesize();
    const thing = `arn:aws:iot:${REGION}:${ACCOUNT}:thing`;
    const command = `arn:aws:iot:${REGION}:${ACCOUNT}:command`;
    expect(resolveArns(statementsOf(template, 'ControlApi'))).toEqual([
      {
        Action: ['dynamodb:GetItem', 'dynamodb:Scan'],
        Effect: 'Allow',
        Resource: {'Fn::GetAtt': [tableKeyedBy(template, 'deviceId'), 'Arn']},
      },
      {
        Action: 'iot:GetThingShadow',
        Effect: 'Allow',
        Resource: [
          `${thing}/*/gps`,
          `${thing}/*/helios`,
          `${thing}/*/net`,
          `${thing}/*/stream`,
        ],
      },
      {
        Action: 'iot:UpdateThingShadow',
        Effect: 'Allow',
        Resource: `${thing}/*/stream`,
      },
      {
        Action: ['iot:StartCommandExecution', 'iot:GetCommandExecution'],
        Effect: 'Allow',
        Resource: [
          `${command}/helios-net-wifi-set-v1`,
          `${command}/helios-net-wifi-remove-v1`,
          `${thing}/*`,
        ],
      },
    ]);
  });

  it('defines the Helios commands with one payload template', () => {
    const template = synthesize();
    const commands = Object.values(template.findResources('AWS::IoT::Command'));
    expect(commands.map(c => c['Properties']['CommandId']).sort()).toEqual([
      'helios-net-wifi-remove-v1',
      'helios-net-wifi-set-v1',
    ]);
    for (const command of commands) {
      const properties = command['Properties'];
      expect(properties['Namespace']).toBe('AWS-IoT');
      expect(properties['Preprocessor']).toEqual({
        AwsJsonSubstitution: {OutputFormat: 'JSON'},
      });
      expect(properties['MandatoryParameters']).toEqual([
        {
          Name: 'args',
          Type: 'STRING',
          Description: "The command's arguments: base64 of their JSON.",
        },
      ]);
      const payload = JSON.parse(properties['PayloadTemplate']);
      expect(payload).toEqual({
        module: 'net',
        name:
          properties['CommandId'] === 'helios-net-wifi-set-v1'
            ? 'wifi.set'
            : 'wifi.remove',
        timeout_s: '${aws:iot:commandexecution::executionTimeoutSec}',
        args: '${aws:iot:commandexecution::parameter:args}',
      });
    }
  });

  it('leaves the device routes open and signs the admin and control routes with IAM', () => {
    const template = synthesize();
    const methods = Object.values(
      template.findResources('AWS::ApiGateway::Method'),
    ).map(method => [
      method['Properties']['HttpMethod'],
      method['Properties']['AuthorizationType'],
    ]);
    expect(methods.sort()).toEqual(
      [
        // Admin (PUT: status, pin, challenge-key).
        ['DELETE', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['PATCH', 'AWS_IAM'],
        ['POST', 'AWS_IAM'],
        ['PUT', 'AWS_IAM'],
        ['PUT', 'AWS_IAM'],
        ['PUT', 'AWS_IAM'],
        // Device.
        ['POST', 'NONE'],
        ['POST', 'NONE'],
        // Control: eight reads, an update, start, stop, the destination, and
        // setting and removing a Wi-Fi network.
        ['GET', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['PUT', 'AWS_IAM'],
        ['DELETE', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['GET', 'AWS_IAM'],
        ['PATCH', 'AWS_IAM'],
        ['POST', 'AWS_IAM'],
        ['POST', 'AWS_IAM'],
        ['PUT', 'AWS_IAM'],
      ].sort(),
    );
    template.allResourcesProperties('AWS::ApiGateway::Method', {
      Integration: {Type: 'AWS_PROXY'},
    });
    const paths = Object.values(
      template.findResources('AWS::ApiGateway::Resource'),
    ).map(resource => resource['Properties']['PathPart']);
    expect(paths.sort()).toEqual(
      [
        'v1',
        'device',
        'bootstrap',
        'challenge',
        'admin',
        'devices',
        '{deviceId}',
        'status',
        'pin',
        'challenge-key',
        'control',
        'devices',
        '{deviceId}',
        'inputs',
        'profiles',
        'location',
        'wifi',
        'networks',
        'streams',
        '{streamId}',
        'start',
        'stop',
        'destination',
      ].sort(),
    );
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
    // Only the three functions' roles.
    template.resourceCountIs('AWS::IAM::Role', 3);
  });
});
