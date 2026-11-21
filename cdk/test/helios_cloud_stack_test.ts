import {Template} from 'aws-cdk-lib/assertions';

import {testStack} from './test_stack';

/** The resource CDK adds to every template for its version reporting. */
const CDK_METADATA = 'AWS::CDK::Metadata';

/** The part of a CloudFormation resource these tests look at. */
interface CfnResource {
  Type: string;
}

function synthesize(): Template {
  return Template.fromStack(testStack());
}

describe('HeliosCloudStack', () => {
  it('holds only nested stacks', () => {
    const resources: Record<string, CfnResource> =
      synthesize().toJSON()['Resources'] ?? {};
    const types = Object.values(resources).map(resource => resource.Type);
    expect(types.filter(type => type !== CDK_METADATA)).toEqual([
      'AWS::CloudFormation::Stack',
      'AWS::CloudFormation::Stack',
      'AWS::CloudFormation::Stack',
    ]);
  });

  it('outputs the bootstrap URL', () => {
    const outputs = synthesize().findOutputs('BootstrapUrl');
    expect(Object.keys(outputs)).toHaveLength(1);
  });

  it('has a description', () => {
    expect(synthesize().toJSON()['Description']).toBe(
      'Helios cloud resources (github.com/kench/helios-cloud)',
    );
  });
});
