/**
 * @fileoverview The Control API for browsers (the Helios console): the same
 * Lambda as the IAM-signed `/v1/control/…` routes, behind an HTTP API that
 * takes access tokens from the identity provider (kench/identity-provider,
 * Amazon Cognito) instead of SigV4.
 *
 * API Gateway's JWT authorizer checks each token's signature, issuer,
 * expiry and audience: only tokens issued for the Helios Control API
 * (`aud` = {@link HELIOS_CONTROL_AUDIENCE}, which a client gets by sending
 * it as RFC 8707's `resource`) pass. Each route then needs a scope: `read`
 * for reads, `write` for changes. A token that fails is answered 401; one
 * without the route's scope, 403.
 *
 * CORS lets only the console's origins call it. Anyone the identity provider
 * signs in may use it for now (owner, 2026-11-27); finer access comes later.
 */

import {Duration} from 'aws-cdk-lib';
import {
  CorsHttpMethod,
  HttpApi,
  HttpMethod,
  HttpStage,
  IpAddressType,
} from 'aws-cdk-lib/aws-apigatewayv2';
import {HttpJwtAuthorizer} from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import {HttpLambdaIntegration} from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import type {IFunction} from 'aws-cdk-lib/aws-lambda';
import {PayloadFormatVersion} from 'aws-cdk-lib/aws-apigatewayv2';
import {Construct} from 'constructs';

/**
 * The Helios Control API's resource identifier in the identity provider:
 * the `aud` of every access token issued for it.
 */
export const HELIOS_CONTROL_AUDIENCE =
  'https://helios-control.services.kenley.tv';

/** A Control API scope: `<audience>/<name>`. */
export function controlScope(name: 'read' | 'write'): string {
  return `${HELIOS_CONTROL_AUDIENCE}/${name}`;
}

/** A Control API route and the scope it needs. */
export interface ControlRoute {
  readonly method: HttpMethod;
  readonly path: string;
  readonly scope: 'read' | 'write';
}

const DEVICE = '/v1/control/devices/{deviceId}';
const STREAM = `${DEVICE}/streams/{streamId}`;

/** Every Control API route (HLD section 7.3), as the REST API has them. */
export const CONTROL_ROUTES: ReadonlyArray<ControlRoute> = [
  {method: HttpMethod.GET, path: '/v1/control/devices', scope: 'read'},
  {method: HttpMethod.GET, path: DEVICE, scope: 'read'},
  {method: HttpMethod.GET, path: `${DEVICE}/inputs`, scope: 'read'},
  {method: HttpMethod.GET, path: `${DEVICE}/profiles`, scope: 'read'},
  {method: HttpMethod.GET, path: `${DEVICE}/location`, scope: 'read'},
  {method: HttpMethod.GET, path: `${DEVICE}/uplinks`, scope: 'read'},
  {method: HttpMethod.GET, path: `${DEVICE}/wifi/networks`, scope: 'read'},
  {method: HttpMethod.PUT, path: `${DEVICE}/wifi/networks`, scope: 'write'},
  {method: HttpMethod.DELETE, path: `${DEVICE}/wifi/networks`, scope: 'write'},
  {method: HttpMethod.GET, path: `${DEVICE}/streams`, scope: 'read'},
  {method: HttpMethod.GET, path: STREAM, scope: 'read'},
  {method: HttpMethod.PATCH, path: STREAM, scope: 'write'},
  {method: HttpMethod.POST, path: `${STREAM}/start`, scope: 'write'},
  {method: HttpMethod.POST, path: `${STREAM}/stop`, scope: 'write'},
  {method: HttpMethod.PUT, path: `${STREAM}/destination`, scope: 'write'},
];

/** Settings for {@link ControlTokenApi}. */
export interface ControlTokenApiProps {
  /** The Control API Lambda. */
  readonly handler: IFunction;
  /**
   * The identity provider's issuer, exactly as tokens carry it in `iss`
   * (`https://issuer-cognito-idp.<region>.amazonaws.com/<user pool ID>`).
   */
  readonly issuer: string;
  /** The origins that may call it from a browser (the console's). */
  readonly allowedOrigins: string[];
  /** Requests a second the stage allows, and its burst. */
  readonly throttle: {readonly rate: number; readonly burst: number};
}

/** The token-authorized HTTP API in front of the Control API Lambda. */
export class ControlTokenApi extends Construct {
  /** The HTTP API. */
  readonly api: HttpApi;

  constructor(scope: Construct, id: string, props: ControlTokenApiProps) {
    super(scope, id);

    this.api = new HttpApi(this, 'Api', {
      apiName: 'helios-control-tokens',
      description:
        'Helios Control API for browsers: identity provider access tokens',
      ipAddressType: IpAddressType.DUAL_STACK,
      // Throttled like the REST API, so the stage is made below.
      createDefaultStage: false,
      corsPreflight: {
        allowOrigins: props.allowedOrigins,
        allowMethods: [
          CorsHttpMethod.GET,
          CorsHttpMethod.PUT,
          CorsHttpMethod.PATCH,
          CorsHttpMethod.POST,
          CorsHttpMethod.DELETE,
        ],
        allowHeaders: ['Authorization', 'Content-Type'],
        maxAge: Duration.hours(1),
      },
    });
    new HttpStage(this, 'Stage', {
      httpApi: this.api,
      autoDeploy: true,
      throttle: {
        rateLimit: props.throttle.rate,
        burstLimit: props.throttle.burst,
      },
    });

    const authorizer = new HttpJwtAuthorizer('Tokens', props.issuer, {
      authorizerName: 'identity-provider',
      jwtAudience: [HELIOS_CONTROL_AUDIENCE],
    });
    // Payload format 1.0: the REST API's event shape, so the Lambda reads
    // both the same way (the token's claims are under the authorizer).
    const integration = new HttpLambdaIntegration('Control', props.handler, {
      payloadFormatVersion: PayloadFormatVersion.VERSION_1_0,
    });
    for (const route of CONTROL_ROUTES) {
      this.api.addRoutes({
        path: route.path,
        methods: [route.method],
        integration,
        authorizer,
        authorizationScopes: [controlScope(route.scope)],
      });
    }
  }

  /** The API's base URL (its `$default` stage). */
  get url(): string {
    return this.api.apiEndpoint;
  }
}
