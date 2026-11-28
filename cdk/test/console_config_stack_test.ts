import {existsSync, readFileSync, readdirSync} from 'node:fs';
import {join} from 'node:path';

import {App} from 'aws-cdk-lib';
import {Template} from 'aws-cdk-lib/assertions';

import {IDENTITY_PROVIDER, testStack} from './test_stack';

function synthesize(): Template {
  return Template.fromStack(testStack().consoleConfig);
}

describe('ConsoleConfigStack', () => {
  it('writes only config.json, never pruning the site, and refreshes it', () => {
    const deployments = Object.values(
      synthesize().findResources('Custom::CDKBucketDeployment'),
    ).map(d => d['Properties']);
    expect(deployments).toHaveLength(1);
    expect(deployments[0]).toMatchObject({
      Prune: false,
      SystemMetadata: {'cache-control': 'no-cache'},
      DistributionPaths: ['/config.json'],
    });
  });

  it('holds the Control API for browsers and the sign-in client', () => {
    const stack = testStack();
    const assembly = (stack.node.root as App).synth();
    const configs = readdirSync(assembly.directory)
      .map(name => join(assembly.directory, name, 'config.json'))
      .filter(path => existsSync(path))
      .map(path => JSON.parse(readFileSync(path, 'utf8')));
    expect(configs).toEqual([
      {
        // Filled in at deploy time with the HTTP API's endpoint.
        apiUrl: expect.stringMatching(/^<<marker:0xbaba:0>>$/),
        auth: {
          domain: IDENTITY_PROVIDER.domain,
          clientId: IDENTITY_PROVIDER.consoleClientId,
          resource: 'https://helios-control.services.kenley.tv',
          scopes: [
            'openid',
            'https://helios-control.services.kenley.tv/read',
            'https://helios-control.services.kenley.tv/write',
          ],
        },
      },
    ]);
    const deployment = Object.values(
      Template.fromStack(stack.consoleConfig).findResources(
        'Custom::CDKBucketDeployment',
      ),
    )[0]!['Properties'];
    expect(JSON.stringify(deployment['SourceMarkers'])).toMatch(
      /ControlTokenApi.*ApiEndpoint/,
    );
  });
});
