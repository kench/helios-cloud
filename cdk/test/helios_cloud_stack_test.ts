import {App} from 'aws-cdk-lib';
import {Template} from 'aws-cdk-lib/assertions';

import {HeliosCloudStack} from '../lib/helios_cloud_stack';

/** The resource CDK adds to every template for its version reporting. */
const CDK_METADATA = 'AWS::CDK::Metadata';

/** The part of a CloudFormation resource these tests look at. */
interface CfnResource {
  Type: string;
}

function synthesize(): Template {
  const app = new App();
  const stack = new HeliosCloudStack(app, 'TestStack', {
    env: {account: '123456789012', region: 'us-west-2'},
  });
  return Template.fromStack(stack);
}

describe('HeliosCloudStack', () => {
  it('creates no resources yet', () => {
    const resources: Record<string, CfnResource> =
      synthesize().toJSON()['Resources'] ?? {};
    const types = Object.values(resources).map(resource => resource.Type);
    expect(types.filter(type => type !== CDK_METADATA)).toEqual([]);
  });

  it('has a description', () => {
    expect(synthesize().toJSON()['Description']).toBe(
      'Helios cloud resources (github.com/kench/helios-cloud)',
    );
  });
});
