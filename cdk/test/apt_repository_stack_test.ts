import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  APT_BUCKET_PREFIX,
  APT_PUBLISHER_ROLE_NAME,
  PACKAGES_PUBLISHER_ROLE_NAME,
  PACKAGES_RELEASE_SUBJECT,
  RELEASE_SUBJECT,
} from '../lib/apt_repository_stack';

import {ACCOUNT, resolveArns, testStack} from './test_stack';

/** The part of a CloudFormation resource these tests look at. */
interface CfnResource {
  Properties: Record<string, unknown>;
}

function synthesize(): Template {
  return Template.fromStack(testStack().aptRepository);
}

/** A publisher role by name, with its logical ID. */
function role(template: Template, name: string): [string, CfnResource] {
  const roles = Object.entries(template.findResources('AWS::IAM::Role'));
  const found = roles.filter(([, r]) => r['Properties']['RoleName'] === name);
  expect(found).toHaveLength(1);
  return found[0] as [string, CfnResource];
}

/** The statements of the policy attached to the role with logical ID `id`. */
function statements(
  template: Template,
  id: string,
): Array<Record<string, unknown>> {
  const policies = Object.values(template.findResources('AWS::IAM::Policy'));
  const attached = policies.filter(policy =>
    JSON.stringify(policy['Properties']['Roles']).includes(`"${id}"`),
  );
  expect(attached).toHaveLength(1);
  return attached[0]['Properties']['PolicyDocument']['Statement'];
}

/** The trust policy a publisher role should have, for `subject`. */
function trust(subject: string): unknown[] {
  return [
    {
      Action: 'sts:AssumeRoleWithWebIdentity',
      Effect: 'Allow',
      Principal: {
        Federated: `arn:aws:iam::${ACCOUNT}:oidc-provider/token.actions.githubusercontent.com`,
      },
      Condition: {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
        },
        StringLike: {'token.actions.githubusercontent.com:sub': subject},
      },
    },
  ];
}

/** The only resource of `type`. */
function only(template: Template, type: string): CfnResource {
  const resources = Object.values(template.findResources(type));
  expect(resources).toHaveLength(1);
  return resources[0] as CfnResource;
}

describe('AptRepositoryStack', () => {
  it('names the bucket in the account-regional namespace', () => {
    synthesize().hasResourceProperties('AWS::S3::Bucket', {
      BucketNamePrefix: APT_BUCKET_PREFIX,
      BucketNamespace: 'account-regional',
      BucketName: Match.absent(),
    });
  });

  it('keeps the bucket private, versioned and retained', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::S3::Bucket', {
      PublicAccessBlockConfiguration: {
        BlockPublicAcls: true,
        BlockPublicPolicy: true,
        IgnorePublicAcls: true,
        RestrictPublicBuckets: true,
      },
      OwnershipControls: {
        Rules: [{ObjectOwnership: 'BucketOwnerEnforced'}],
      },
      VersioningConfiguration: {Status: 'Enabled'},
      LifecycleConfiguration: {
        Rules: [
          Match.objectLike({
            NoncurrentVersionExpiration: {NoncurrentDays: 30},
          }),
        ],
      },
    });
    template.hasResource('AWS::S3::Bucket', {
      DeletionPolicy: 'Retain',
      UpdateReplacePolicy: 'Retain',
    });
  });

  it('lets only CloudFront read the bucket, over TLS', () => {
    const policy = only(synthesize(), 'AWS::S3::BucketPolicy');
    const statements = (
      policy.Properties['PolicyDocument'] as {Statement: unknown[]}
    ).Statement;
    expect(statements).toEqual([
      expect.objectContaining({
        Effect: 'Deny',
        Condition: {Bool: {'aws:SecureTransport': 'false'}},
      }),
      expect.objectContaining({
        Effect: 'Deny',
        Condition: {NumericLessThan: {'s3:TlsVersion': 1.2}},
      }),
      expect.objectContaining({
        Effect: 'Allow',
        Action: 's3:GetObject',
        Principal: {Service: 'cloudfront.amazonaws.com'},
      }),
    ]);
  });

  it('serves the bucket over HTTPS through origin access control', () => {
    const template = synthesize();
    template.resourceCountIs('AWS::CloudFront::OriginAccessControl', 1);
    template.hasResourceProperties('AWS::CloudFront::Distribution', {
      DistributionConfig: Match.objectLike({
        Aliases: Match.absent(),
        WebACLId: Match.absent(),
        PriceClass: 'PriceClass_100',
        DefaultCacheBehavior: Match.objectLike({
          ViewerProtocolPolicy: 'redirect-to-https',
          // The managed CachingOptimized policy.
          CachePolicyId: '658327ea-f89d-4fab-a63d-7e88639e58f6',
        }),
      }),
    });
  });

  it('has exactly the two publisher roles', () => {
    synthesize().resourceCountIs('AWS::IAM::Role', 2);
  });

  it('lets only the helios repo publish to stable, from v* tags', () => {
    const template = synthesize();
    const [, publisher] = role(template, APT_PUBLISHER_ROLE_NAME);
    expect(RELEASE_SUBJECT).toBe(
      'repo:kench@34564/helios@1464316682:ref:refs/tags/v*',
    );
    expect(
      resolveArns(
        (
          publisher.Properties['AssumeRolePolicyDocument'] as {
            Statement: unknown[];
          }
        ).Statement,
      ),
    ).toEqual(trust(RELEASE_SUBJECT));
    expect(publisher.Properties['MaxSessionDuration']).toBe(3600);
  });

  it('lets the helios publisher write the repository paths and invalidate', () => {
    const template = synthesize();
    const [id] = role(template, APT_PUBLISHER_ROLE_NAME);
    const policy = statements(template, id);
    expect(policy.map(statement => statement['Action'])).toEqual([
      's3:ListBucket',
      ['s3:GetObject', 's3:PutObject'],
      'cloudfront:CreateInvalidation',
    ]);
    expect(policy[0]['Condition']).toEqual({
      StringLike: {'s3:prefix': ['pool/*', 'dists/*', 'keys/*']},
    });
    const objects = JSON.stringify(policy[1]['Resource']);
    for (const prefix of ['/pool/*', '/dists/*', '/keys/*']) {
      expect(objects).toContain(prefix);
    }
    expect(JSON.stringify(policy)).not.toContain('s3:DeleteObject');
  });

  it('lets only helios-packages publish to trixie, from release tags', () => {
    const template = synthesize();
    const [, publisher] = role(template, PACKAGES_PUBLISHER_ROLE_NAME);
    expect(PACKAGES_RELEASE_SUBJECT).toBe(
      'repo:kench@34564/helios-packages@1466772735:ref:refs/tags/*/*',
    );
    expect(
      resolveArns(
        (
          publisher.Properties['AssumeRolePolicyDocument'] as {
            Statement: unknown[];
          }
        ).Statement,
      ),
    ).toEqual(trust(PACKAGES_RELEASE_SUBJECT));
    expect(publisher.Properties['MaxSessionDuration']).toBe(3600);
  });

  it('keeps the helios-packages publisher to its own suite and key', () => {
    const template = synthesize();
    const [id] = role(template, PACKAGES_PUBLISHER_ROLE_NAME);
    const policy = statements(template, id);
    expect(policy.map(statement => statement['Action'])).toEqual([
      's3:ListBucket',
      ['s3:GetObject', 's3:PutObject'],
      'cloudfront:CreateInvalidation',
    ]);
    expect(policy[0]['Condition']).toEqual({
      StringLike: {
        's3:prefix': [
          'pool/trixie/*',
          'dists/trixie/*',
          'keys/helios-packages-archive-keyring.asc',
        ],
      },
    });
    const objects = JSON.stringify(policy[1]['Resource']);
    for (const path of [
      '/pool/trixie/*',
      '/dists/trixie/*',
      '/keys/helios-packages-archive-keyring.asc',
    ]) {
      expect(objects).toContain(path);
    }
    for (const other of ['/pool/*', '/dists/*', '/keys/*', '/dists/stable']) {
      expect(objects).not.toContain(`${other}"`);
    }
    expect(JSON.stringify(policy)).not.toContain('s3:DeleteObject');
  });
});
