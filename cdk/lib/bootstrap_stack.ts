/**
 * @fileoverview The bootstrap service (Bootstrap API, section 7): the device,
 * admin and control API Lambdas behind one API Gateway REST API, and the
 * DynamoDB tables for device records, the MAC index and challenges. The
 * control API (HLD section 7.3) lives here, under /v1/control, so the three
 * share one endpoint, stage and (later) domain and WAF.
 *
 * Temporary simplifications (Bootstrap API, section 7): the API uses its
 * default execute-api domain (no custom domain or ACM certificate), PINs are
 * stored in the devices table with DynamoDB's encryption at rest rather than
 * a dedicated KMS key, and there's no AWS WAF, only stage throttling.
 */

import {Duration, NestedStack, RemovalPolicy} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {
  AuthorizationType,
  EndpointType,
  LambdaIntegration,
  RestApi,
} from 'aws-cdk-lib/aws-apigateway';
import {
  AttributeType,
  Billing,
  ProjectionType,
  TableClass,
  TableV2,
} from 'aws-cdk-lib/aws-dynamodb';
import {Grant, PolicyStatement} from 'aws-cdk-lib/aws-iam';
import type {IGrantable} from 'aws-cdk-lib/aws-iam';
import {CfnCommand} from 'aws-cdk-lib/aws-iot';
import {Architecture, Function, Runtime} from 'aws-cdk-lib/aws-lambda';
import type {Code} from 'aws-cdk-lib/aws-lambda';
import {LogGroup, RetentionDays} from 'aws-cdk-lib/aws-logs';
import type {Construct} from 'constructs';

/**
 * The API stage. With the default execute-api domain the stage is part of
 * the URL, so Helios's `bootstrap.url` is `<api>/dev/v1`.
 */
export const BOOTSTRAP_STAGE = 'dev';

/** Stage throttling, until AWS WAF rate limits are added. */
export const THROTTLE_RATE_PER_SECOND = 10;
export const THROTTLE_BURST = 20;

/** The device API Lambda's handler (lambda/device-api). */
export const DEVICE_API_HANDLER =
  'tv.kenley.helios.cloud.device.DeviceApiHandler::handleRequest';

/** The admin API Lambda's handler (lambda/admin-api). */
export const ADMIN_API_HANDLER =
  'tv.kenley.helios.cloud.admin.AdminApiHandler::handleRequest';

/** The control API Lambda's handler (lambda/control-api). */
export const CONTROL_API_HANDLER =
  'tv.kenley.helios.cloud.control.ControlApiHandler::handleRequest';

/**
 * The device shadows the control API reads (`net` for the Wi-Fi networks); it
 * writes only `stream`.
 */
export const CONTROL_READ_SHADOWS: ReadonlyArray<string> = [
  'helios',
  'net',
  'stream',
];
export const CONTROL_WRITE_SHADOWS: ReadonlyArray<string> = ['stream'];

/**
 * The Helios commands the control API sends as AWS IoT command executions
 * (LLD 2, section 7.3): [module, name within the module].
 */
export const IOT_COMMANDS: ReadonlyArray<[string, string]> = [
  ['net', 'wifi.set'],
  ['net', 'wifi.remove'],
];

/**
 * The version of every Helios command's AWS IoT definition, in its ID. A new
 * payload template means a new version: AWS can't change a command's payload in
 * place, and keeps a deleted command's ID for 12 hours (the first deploy's
 * rollback left `helios-net-wifi-set` taken).
 */
export const COMMAND_VERSION = 'v1';

/** The AWS IoT command ID for a Helios command: `helios-net-wifi-set-v1`. */
export function commandId(module: string, name: string): string {
  return `helios-${module}-${name.replace(/\./g, '-')}-${COMMAND_VERSION}`;
}

/**
 * Every Helios command's payload template: the module and name fixed, the
 * execution's timeout, and the arguments as one base64 string of their JSON,
 * so nothing in them needs escaping here. Placeholders sit in strings, since
 * the template must be valid JSON before substitution.
 */
export function payloadTemplate(module: string, name: string): string {
  return JSON.stringify({
    module,
    name,
    timeout_s: '${aws:iot:commandexecution::executionTimeoutSec}',
    args: '${aws:iot:commandexecution::parameter:args}',
  });
}

/**
 * The admin API routes provisioning needs: RegisterDevice and GetDevice, and,
 * to re-provision a registered device (a new token for the same thing and
 * device ID), SetDevicePin and PutChallengeKey.
 */
export const PROVISIONING_ROUTES: ReadonlyArray<[string, string]> = [
  ['POST', '/v1/admin/devices'],
  ['GET', '/v1/admin/devices/*'],
  ['PUT', '/v1/admin/devices/*/pin'],
  ['PUT', '/v1/admin/devices/*/challenge-key'],
];

/**
 * Every table: the Standard table class and on-demand billing. No
 * point-in-time recovery.
 */
const TABLE_SETTINGS = {
  tableClass: TableClass.STANDARD,
  billing: Billing.onDemand(),
};

/** AWS settings sent to devices whose record has none of its own. */
export interface DefaultAwsSettings {
  /** The AWS IoT Core data endpoint (`iot:Data-ATS`). */
  readonly iotEndpoint: string;
  /** The AWS IoT credentials provider endpoint. */
  readonly credentialsEndpoint: string;
  /** The role alias devices get credentials through. */
  readonly roleAlias: string;
}

/** Settings for {@link BootstrapStack}. */
export interface BootstrapStackProps extends NestedStackProps {
  /** The device API's deployment package (`gradlew :device-api:lambdaZip`). */
  readonly deviceApiCode: Code;
  /** The admin API's deployment package (`gradlew :admin-api:lambdaZip`). */
  readonly adminApiCode: Code;
  /** The control API's deployment package (`gradlew :control-api:lambdaZip`). */
  readonly controlApiCode: Code;
  /** AWS settings for devices whose record has none. */
  readonly defaultAws: DefaultAwsSettings;
}

/** The nested stack for the bootstrap service. */
export class BootstrapStack extends NestedStack {
  /** Device records, keyed by deviceId, including PINs. */
  readonly devices: TableV2;

  /** One item per MAC, pointing to its device record. */
  readonly macIndex: TableV2;

  /** Issued challenges, removed by TTL after they expire. */
  readonly challenges: TableV2;

  /** The device API Lambda. */
  readonly deviceApi: Function;

  /** The admin API Lambda. */
  readonly adminApi: Function;

  /** The control API Lambda. */
  readonly controlApi: Function;

  /** The REST API. */
  readonly api: RestApi;

  /** The base URL for Helios's `bootstrap.url`, ending in `/v1`. */
  readonly bootstrapUrl: string;

  constructor(scope: Construct, id: string, props: BootstrapStackProps) {
    super(scope, id, {
      description: 'Helios bootstrap service: device API, Lambda, DynamoDB',
      ...props,
    });

    // Device records and the MAC index are the service's state: keep them if
    // the stack is ever deleted.
    this.devices = new TableV2(this, 'Devices', {
      ...TABLE_SETTINGS,
      partitionKey: {name: 'deviceId', type: AttributeType.STRING},
      globalSecondaryIndexes: [
        {
          // For the admin API's ListDevices?thingName=… and uniqueness checks.
          indexName: 'thingName',
          partitionKey: {name: 'thingName', type: AttributeType.STRING},
          projectionType: ProjectionType.KEYS_ONLY,
        },
      ],
      removalPolicy: RemovalPolicy.RETAIN,
    });
    this.macIndex = new TableV2(this, 'MacIndex', {
      ...TABLE_SETTINGS,
      partitionKey: {name: 'mac', type: AttributeType.STRING},
      removalPolicy: RemovalPolicy.RETAIN,
    });
    this.challenges = new TableV2(this, 'Challenges', {
      ...TABLE_SETTINGS,
      partitionKey: {name: 'challengeId', type: AttributeType.STRING},
      timeToLiveAttribute: 'expiresAt',
      removalPolicy: RemovalPolicy.DESTROY,
    });

    // The function's log group is also the audit log (Bootstrap API,
    // section 5): 30 days, the closest CloudWatch period to four weeks.
    const logGroup = new LogGroup(this, 'DeviceApiLogs', {
      retention: RetentionDays.ONE_MONTH,
    });
    this.deviceApi = new Function(this, 'DeviceApi', {
      description:
        'Helios bootstrap service: device API (Bootstrap, AnswerChallenge)',
      runtime: Runtime.JAVA_25,
      architecture: Architecture.ARM_64,
      handler: DEVICE_API_HANDLER,
      code: props.deviceApiCode,
      memorySize: 1024,
      // Helios gives up on a request after 10 s (LLD 1, section 3).
      timeout: Duration.seconds(10),
      logGroup,
      environment: {
        DEVICES_TABLE: this.devices.tableName,
        MAC_INDEX_TABLE: this.macIndex.tableName,
        CHALLENGES_TABLE: this.challenges.tableName,
        DEFAULT_IOT_ENDPOINT: props.defaultAws.iotEndpoint,
        DEFAULT_CREDENTIALS_ENDPOINT: props.defaultAws.credentialsEndpoint,
        DEFAULT_ROLE_ALIAS: props.defaultAws.roleAlias,
        // Faster cold starts for a short-lived request handler.
        JAVA_TOOL_OPTIONS: '-XX:+TieredCompilation -XX:TieredStopAtLevel=1',
      },
    });
    this.grantDeviceApi();

    this.adminApi = new Function(this, 'AdminApi', {
      description:
        'Helios bootstrap service: admin API (RegisterDevice, GetDevice, ...)',
      runtime: Runtime.JAVA_25,
      architecture: Architecture.ARM_64,
      handler: ADMIN_API_HANDLER,
      code: props.adminApiCode,
      memorySize: 1024,
      timeout: Duration.seconds(30),
      // Its own log group: the admin audit log, with the caller of each call.
      logGroup: new LogGroup(this, 'AdminApiLogs', {
        retention: RetentionDays.ONE_MONTH,
      }),
      environment: {
        DEVICES_TABLE: this.devices.tableName,
        MAC_INDEX_TABLE: this.macIndex.tableName,
        JAVA_TOOL_OPTIONS: '-XX:+TieredCompilation -XX:TieredStopAtLevel=1',
      },
    });
    this.grantAdminApi();

    this.controlApi = new Function(this, 'ControlApi', {
      description:
        'Helios control API: devices and their streams (ListDevices, StartStream, ...)',
      runtime: Runtime.JAVA_25,
      architecture: Architecture.ARM_64,
      handler: CONTROL_API_HANDLER,
      code: props.controlApiCode,
      memorySize: 1024,
      timeout: Duration.seconds(15),
      // Its own log group: the control audit log, with the caller of each
      // call and every destination change.
      logGroup: new LogGroup(this, 'ControlApiLogs', {
        retention: RetentionDays.ONE_MONTH,
      }),
      environment: {
        DEVICES_TABLE: this.devices.tableName,
        IOT_DATA_ENDPOINT: props.defaultAws.iotEndpoint,
        // For the Helios commands' and things' ARNs.
        IOT_ARN_PREFIX: `arn:${this.partition}:iot:${this.region}:${this.account}`,
        JAVA_TOOL_OPTIONS: '-XX:+TieredCompilation -XX:TieredStopAtLevel=1',
      },
    });
    for (const [module, name] of IOT_COMMANDS) {
      new CfnCommand(this, `Command-${commandId(module, name)}`, {
        commandId: commandId(module, name),
        namespace: 'AWS-IoT',
        displayName: `Helios ${module}.${name}`,
        description: `Helios's ${module}.${name} command, sent by the control API.`,
        payloadTemplate: payloadTemplate(module, name),
        preprocessor: {awsJsonSubstitution: {outputFormat: 'JSON'}},
        mandatoryParameters: [
          {
            name: 'args',
            type: 'STRING',
            description: "The command's arguments: base64 of their JSON.",
          },
        ],
      });
    }
    this.grantControlApi();

    this.api = new RestApi(this, 'Api', {
      restApiName: 'helios-bootstrap',
      description: 'Helios bootstrap service (Bootstrap API)',
      endpointConfiguration: {types: [EndpointType.REGIONAL]},
      // No account-wide API Gateway logging role: that setting is shared
      // with the account's other APIs. The Lambda logs every call.
      cloudWatchRole: false,
      deployOptions: {
        stageName: BOOTSTRAP_STAGE,
        throttlingRateLimit: THROTTLE_RATE_PER_SECOND,
        throttlingBurstLimit: THROTTLE_BURST,
      },
    });
    const integration = new LambdaIntegration(this.deviceApi, {
      allowTestInvoke: false,
    });
    // Device routes have no authorizer: before it has the PIN, a device has
    // no credential to present (Bootstrap API, section 3).
    const bootstrap = this.api.root
      .addResource('v1')
      .addResource('device')
      .addResource('bootstrap');
    bootstrap.addMethod('POST', integration);
    bootstrap.addResource('challenge').addMethod('POST', integration);

    // Admin routes need a SigV4 signature from an IAM principal allowed to
    // call them (Bootstrap API, section 4).
    const admin = new LambdaIntegration(this.adminApi, {
      allowTestInvoke: false,
    });
    const iam = {authorizationType: AuthorizationType.IAM};
    const devices = this.api.root
      .getResource('v1')!
      .addResource('admin')
      .addResource('devices');
    devices.addMethod('POST', admin, iam);
    const device = devices.addResource('{deviceId}');
    device.addMethod('GET', admin, iam);
    device.addMethod('DELETE', admin, iam);
    device.addResource('status').addMethod('PUT', admin, iam);

    // Control routes (HLD section 7.3): SigV4 too; callers assume
    // helios-operator (grantControl).
    const control = new LambdaIntegration(this.controlApi, {
      allowTestInvoke: false,
    });
    const controlDevices = this.api.root
      .getResource('v1')!
      .addResource('control')
      .addResource('devices');
    controlDevices.addMethod('GET', control, iam);
    const controlDevice = controlDevices.addResource('{deviceId}');
    controlDevice.addMethod('GET', control, iam);
    controlDevice.addResource('inputs').addMethod('GET', control, iam);
    controlDevice.addResource('profiles').addMethod('GET', control, iam);
    const wifiNetworks = controlDevice
      .addResource('wifi')
      .addResource('networks');
    for (const method of ['GET', 'PUT', 'DELETE']) {
      wifiNetworks.addMethod(method, control, iam);
    }
    const streams = controlDevice.addResource('streams');
    streams.addMethod('GET', control, iam);
    const stream = streams.addResource('{streamId}');
    stream.addMethod('GET', control, iam);
    stream.addMethod('PATCH', control, iam);
    stream.addResource('start').addMethod('POST', control, iam);
    stream.addResource('stop').addMethod('POST', control, iam);
    stream.addResource('destination').addMethod('PUT', control, iam);

    this.bootstrapUrl = `${this.api.url}v1`;
  }

  /**
   * Lets {@code grantee} call the admin routes provisioning needs
   * (RegisterDevice, GetDevice, SetDevicePin and PutChallengeKey), and no
   * others.
   */
  grantProvisioning(grantee: IGrantable): Grant {
    return Grant.addToPrincipal({
      grantee,
      actions: ['execute-api:Invoke'],
      resourceArns: PROVISIONING_ROUTES.map(([method, path]) =>
        this.api.arnForExecuteApi(method, path, BOOTSTRAP_STAGE),
      ),
    });
  }

  /** Lets {@code grantee} call every control route, and nothing else here. */
  grantControl(grantee: IGrantable): Grant {
    return Grant.addToPrincipal({
      grantee,
      actions: ['execute-api:Invoke'],
      resourceArns: [
        this.api.arnForExecuteApi('*', '/v1/control/*', BOOTSTRAP_STAGE),
      ],
    });
  }

  /**
   * Only what ControlService does: read device records (never write them),
   * read the `helios`, `net` and `stream` shadows, write the `stream` shadow,
   * and start and read executions of the Helios commands.
   */
  private grantControlApi(): void {
    const shadows = (names: ReadonlyArray<string>) =>
      names.map(name =>
        this.formatArn({
          service: 'iot',
          resource: 'thing',
          resourceName: `*/${name}`,
        }),
      );
    for (const statement of [
      new PolicyStatement({
        actions: ['dynamodb:GetItem', 'dynamodb:Scan'],
        resources: [this.devices.tableArn],
      }),
      new PolicyStatement({
        actions: ['iot:GetThingShadow'],
        resources: shadows(CONTROL_READ_SHADOWS),
      }),
      new PolicyStatement({
        actions: ['iot:UpdateThingShadow'],
        resources: shadows(CONTROL_WRITE_SHADOWS),
      }),
      new PolicyStatement({
        actions: ['iot:StartCommandExecution', 'iot:GetCommandExecution'],
        resources: [
          ...IOT_COMMANDS.map(([module, name]) =>
            this.formatArn({
              service: 'iot',
              resource: 'command',
              resourceName: commandId(module, name),
            }),
          ),
          this.formatArn({
            service: 'iot',
            resource: 'thing',
            resourceName: '*',
          }),
        ],
      }),
    ]) {
      this.controlApi.addToRolePolicy(statement);
    }
  }

  /** Only the DynamoDB calls AdminStore makes. */
  private grantAdminApi(): void {
    for (const statement of [
      new PolicyStatement({
        actions: [
          'dynamodb:GetItem',
          'dynamodb:PutItem',
          'dynamodb:UpdateItem',
          'dynamodb:DeleteItem',
        ],
        resources: [this.devices.tableArn],
      }),
      new PolicyStatement({
        actions: ['dynamodb:Query'],
        resources: [`${this.devices.tableArn}/index/thingName`],
      }),
      new PolicyStatement({
        actions: ['dynamodb:PutItem', 'dynamodb:DeleteItem'],
        resources: [this.macIndex.tableArn],
      }),
    ]) {
      this.adminApi.addToRolePolicy(statement);
    }
  }

  /** Only the DynamoDB calls DeviceStore makes. */
  private grantDeviceApi(): void {
    for (const statement of [
      new PolicyStatement({
        actions: ['dynamodb:BatchGetItem'],
        resources: [this.macIndex.tableArn],
      }),
      new PolicyStatement({
        actions: ['dynamodb:GetItem'],
        resources: [this.devices.tableArn],
      }),
      // Recording lastSeen is the only write to a device record, so the
      // function can't change PINs or anything else.
      new PolicyStatement({
        actions: ['dynamodb:UpdateItem'],
        resources: [this.devices.tableArn],
        conditions: {
          'ForAllValues:StringEquals': {
            'dynamodb:Attributes': ['deviceId', 'lastSeen'],
          },
        },
      }),
      new PolicyStatement({
        actions: ['dynamodb:PutItem', 'dynamodb:UpdateItem'],
        resources: [this.challenges.tableArn],
      }),
    ]) {
      this.deviceApi.addToRolePolicy(statement);
    }
  }
}
