import {Match, Template} from 'aws-cdk-lib/assertions';

import {
  APT_BUCKET_PREFIX,
  APT_PUBLISHER_ROLE_NAME,
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

  it('lets only the helios repo publish, from v* tags', () => {
    const role = only(synthesize(), 'AWS::IAM::Role');
    expect(role.Properties['RoleName']).toBe(APT_PUBLISHER_ROLE_NAME);
    expect(RELEASE_SUBJECT).toBe(
      'repo:kench@34564/helios@1464316682:ref:refs/tags/v*',
    );
    expect(
      resolveArns(
        (role.Properties['AssumeRolePolicyDocument'] as {Statement: unknown[]})
          .Statement,
      ),
    ).toEqual([
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
          StringLike: {
            'token.actions.githubusercontent.com:sub': RELEASE_SUBJECT,
          },
        },
      },
    ]);
    expect(role.Properties['MaxSessionDuration']).toBe(3600);
  });

  it('lets the publisher write only the repository paths and invalidate', () => {
    const policy = only(synthesize(), 'AWS::IAM::Policy');
    const statements = (
      policy.Properties['PolicyDocument'] as {
        Statement: Array<Record<string, unknown>>;
      }
    ).Statement;
    expect(statements.map(statement => statement['Action'])).toEqual([
      's3:ListBucket',
      ['s3:GetObject', 's3:PutObject'],
      'cloudfront:CreateInvalidation',
    ]);
    expect(statements[0]['Condition']).toEqual({
      StringLike: {'s3:prefix': ['pool/*', 'dists/*', 'keys/*']},
    });
    const objects = JSON.stringify(statements[1]['Resource']);
    for (const prefix of ['/pool/*', '/dists/*', '/keys/*']) {
      expect(objects).toContain(prefix);
    }
    expect(JSON.stringify(statements)).not.toContain('s3:DeleteObject');
  });
});
