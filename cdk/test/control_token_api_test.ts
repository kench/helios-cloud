import {Template} from 'aws-cdk-lib/assertions';

import {CONSOLE_DEV_ORIGIN} from '../lib/console_stack';
import {
  CONTROL_ROUTES,
  HELIOS_CONTROL_AUDIENCE,
  controlScope,
} from '../lib/control_token_api';

import {ISSUER, testStack} from './test_stack';

function synthesize(): Template {
  return Template.fromStack(testStack().bootstrap);
}

/** The resources of `type`, by logical ID. */
function resources(
  template: Template,
  type: string,
): Array<Record<string, unknown>> {
  return Object.values(template.findResources(type)).map(
    r => r['Properties'] as Record<string, unknown>,
  );
}

describe('ControlTokenApi', () => {
  it('accepts only access tokens for the Helios Control API from the identity provider', () => {
    const authorizers = resources(
      synthesize(),
      'AWS::ApiGatewayV2::Authorizer',
    );
    expect(authorizers).toHaveLength(1);
    expect(authorizers[0]).toMatchObject({
      AuthorizerType: 'JWT',
      Name: 'identity-provider',
      IdentitySource: ['$request.header.Authorization'],
      JwtConfiguration: {
        Issuer: ISSUER,
        Audience: [HELIOS_CONTROL_AUDIENCE],
      },
    });
    expect(HELIOS_CONTROL_AUDIENCE).toBe(
      'https://helios-control.services.kenley.tv',
    );
  });

  it('has every control route, reads needing read and changes write', () => {
    const routes = resources(synthesize(), 'AWS::ApiGatewayV2::Route');
    const keys = routes.map(r => [
      r['RouteKey'],
      r['AuthorizationType'],
      r['AuthorizationScopes'],
    ]);
    expect(keys.sort()).toEqual(
      CONTROL_ROUTES.map(r => [
        `${r.method} ${r.path}`,
        'JWT',
        [controlScope(r.method === 'GET' ? 'read' : 'write')],
      ]).sort(),
    );
    // The same routes as the IAM-signed REST API's /v1/control.
    expect(CONTROL_ROUTES).toHaveLength(15);
  });

  it('calls the control API Lambda with the REST event shape', () => {
    const template = synthesize();
    const integrations = resources(template, 'AWS::ApiGatewayV2::Integration');
    expect(integrations.length).toBeGreaterThan(0);
    for (const integration of integrations) {
      expect(integration).toMatchObject({
        IntegrationType: 'AWS_PROXY',
        PayloadFormatVersion: '1.0',
        IntegrationUri: {
          'Fn::GetAtt': [expect.stringMatching(/^ControlApi/), 'Arn'],
        },
      });
    }
  });

  it("lets only the console's origins call it from a browser", () => {
    const [api] = resources(synthesize(), 'AWS::ApiGatewayV2::Api');
    expect(api).toMatchObject({
      Name: 'helios-control-tokens',
      ProtocolType: 'HTTP',
      IpAddressType: 'dualstack',
      CorsConfiguration: {
        AllowHeaders: ['Authorization', 'Content-Type'],
        AllowMethods: ['GET', 'PUT', 'PATCH', 'POST', 'DELETE'],
        MaxAge: 3600,
      },
    });
    const origins = (api!['CorsConfiguration'] as Record<string, unknown>)[
      'AllowOrigins'
    ] as unknown[];
    expect(origins).toHaveLength(2);
    expect(JSON.stringify(origins[0])).toMatch(
      /^\{"Fn::Join":\["",\["https:\/\/"/,
    );
    expect(origins[1]).toBe(CONSOLE_DEV_ORIGIN);
    expect(api!['CorsConfiguration']).not.toHaveProperty('AllowCredentials');
  });

  it('is throttled like the REST API, on its $default stage', () => {
    const stages = resources(synthesize(), 'AWS::ApiGatewayV2::Stage');
    expect(stages).toEqual([
      expect.objectContaining({
        StageName: '$default',
        AutoDeploy: true,
        DefaultRouteSettings: {
          ThrottlingRateLimit: 10,
          ThrottlingBurstLimit: 20,
        },
      }),
    ]);
  });
});
