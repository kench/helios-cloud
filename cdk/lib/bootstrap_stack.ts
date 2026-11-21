/**
 * @fileoverview The bootstrap service (Bootstrap API, section 7): the device
 * API Lambda behind an API Gateway REST API, and the DynamoDB tables for
 * device records, the MAC index and challenges.
 *
 * Temporary simplifications (Bootstrap API, section 7): the API uses its
 * default execute-api domain (no custom domain or ACM certificate), PINs are
 * stored in the devices table with DynamoDB's encryption at rest rather than
 * a dedicated KMS key, and there's no AWS WAF, only stage throttling.
 */

import {Duration, NestedStack, RemovalPolicy} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {
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
import {PolicyStatement} from 'aws-cdk-lib/aws-iam';
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

    this.bootstrapUrl = `${this.api.url}v1`;
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
