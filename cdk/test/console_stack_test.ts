import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';

import {App, Stack} from 'aws-cdk-lib';
import {Match, Template} from 'aws-cdk-lib/assertions';
import {Source} from 'aws-cdk-lib/aws-s3-deployment';

import {
  CONSOLE_BUCKET_PREFIX,
  ConsoleStack,
  MAX_CSP_LENGTH,
  contentSecurityPolicy,
  inlineScriptHashes,
} from '../lib/console_stack';

import {CONSOLE_FIXTURE, REGION, testStack} from './test_stack';

const INDEX_HTML = readFileSync(join(CONSOLE_FIXTURE, 'index.html'), 'utf8');

function synthesize(): Template {
  return Template.fromStack(testStack().console);
}

function sha256(text: string): string {
  return `'sha256-${createHash('sha256').update(text, 'utf8').digest('base64')}'`;
}

describe('ConsoleStack', () => {
  it('keeps the site in a private, encrypted bucket', () => {
    synthesize().hasResourceProperties('AWS::S3::Bucket', {
      BucketNamePrefix: CONSOLE_BUCKET_PREFIX,
      BucketName: Match.absent(),
      BucketNamespace: 'account-regional',
      PublicAccessBlockConfiguration: {
        BlockPublicAcls: true,
        BlockPublicPolicy: true,
        IgnorePublicAcls: true,
        RestrictPublicBuckets: true,
      },
      OwnershipControls: {
        Rules: [{ObjectOwnership: 'BucketOwnerEnforced'}],
      },
      BucketEncryption: {
        ServerSideEncryptionConfiguration: [
          {ServerSideEncryptionByDefault: {SSEAlgorithm: 'AES256'}},
        ],
      },
    });
  });

  it('serves it over HTTPS at the default domain, with index.html for app routes', () => {
    const template = synthesize();
    template.hasResourceProperties('AWS::CloudFront::Distribution', {
      DistributionConfig: Match.objectLike({
        DefaultRootObject: 'index.html',
        HttpVersion: 'http2and3',
        PriceClass: 'PriceClass_100',
        Aliases: Match.absent(),
        ViewerCertificate: Match.absent(),
        DefaultCacheBehavior: Match.objectLike({
          ViewerProtocolPolicy: 'redirect-to-https',
        }),
        CustomErrorResponses: [403, 404].map(code => ({
          ErrorCode: code,
          ResponseCode: 200,
          ResponsePagePath: '/index.html',
          ErrorCachingMinTTL: 0,
        })),
      }),
    });
    template.resourceCountIs('AWS::CloudFront::OriginAccessControl', 1);
  });

  it('sends a content security policy with the inline scripts by hash', () => {
    const template = synthesize();
    const policies = Object.values(
      template.findResources('AWS::CloudFront::ResponseHeadersPolicy'),
    );
    expect(policies).toHaveLength(1);
    const security =
      policies[0]['Properties']['ResponseHeadersPolicyConfig'][
        'SecurityHeadersConfig'
      ];
    expect(security['ContentSecurityPolicy']).toEqual({
      ContentSecurityPolicy: contentSecurityPolicy(
        inlineScriptHashes(INDEX_HTML),
      ),
      Override: true,
    });
    expect(security['FrameOptions']).toEqual({
      FrameOption: 'DENY',
      Override: true,
    });
    expect(security['StrictTransportSecurity']).toMatchObject({
      AccessControlMaxAgeSec: 31536000,
      Override: true,
    });
  });

  it('caches hashed files for a year and checks pages on every load', () => {
    const deployments = Object.values(
      synthesize().findResources('Custom::CDKBucketDeployment'),
    ).map(d => d['Properties']);
    expect(deployments).toHaveLength(2);
    const [hashed, pages] = [
      deployments.find(d => JSON.stringify(d['Include']) === '["assets/*"]'),
      deployments.find(d => JSON.stringify(d['Exclude']) === '["assets/*"]'),
    ];
    expect(hashed?.['SystemMetadata']).toEqual({
      'cache-control': 'public, max-age=31536000, immutable',
    });
    expect(hashed?.['DistributionId']).toBeUndefined();
    expect(pages?.['SystemMetadata']).toEqual({'cache-control': 'no-cache'});
    expect(pages?.['DistributionPaths']).toEqual(['/*']);
  });

  it('uses a custom domain and its us-east-1 certificate when given one', () => {
    const certificateArn = 'arn:aws:acm:us-east-1:123456789012:certificate/abc';
    const stack = new Stack(new App(), 'Parent', {
      env: {account: '123456789012', region: REGION},
    });
    const console = new ConsoleStack(stack, 'Console', {
      site: Source.asset(CONSOLE_FIXTURE),
      indexHtml: INDEX_HTML,
      domain: {names: ['console.example.com'], certificateArn},
    });
    Template.fromStack(console).hasResourceProperties(
      'AWS::CloudFront::Distribution',
      {
        DistributionConfig: Match.objectLike({
          Aliases: ['console.example.com'],
          ViewerCertificate: Match.objectLike({
            AcmCertificateArn: certificateArn,
            SslSupportMethod: 'sni-only',
          }),
        }),
      },
    );
  });
});

describe('inlineScriptHashes', () => {
  it('hashes each inline script once and skips scripts with a src', () => {
    expect(inlineScriptHashes(INDEX_HTML)).toEqual([
      sha256('window.ready = true;'),
      sha256('import "/assets/app-abc123.js";'),
    ]);
    expect(inlineScriptHashes('<script SRC="/a.js"></script><p>')).toEqual([]);
  });
});

describe('contentSecurityPolicy', () => {
  it("allows only the console's own origin", () => {
    expect(contentSecurityPolicy([])).toBe(
      "default-src 'self'; script-src 'self'; style-src 'self'; " +
        "img-src 'self' data:; connect-src 'self'; font-src 'self'; " +
        "object-src 'none'; base-uri 'self'; form-action 'self'; " +
        "frame-ancestors 'none'",
    );
  });

  it("refuses a policy longer than CloudFront's limit", () => {
    const many = Array.from({length: 40}, (_, n) => sha256(`s${n}`));
    expect(contentSecurityPolicy(many.slice(0, 5)).length).toBeLessThan(
      MAX_CSP_LENGTH,
    );
    expect(() => contentSecurityPolicy(many)).toThrow(/over CloudFront/);
  });
});
