/**
 * @fileoverview The Helios console: the web UI for the Control API
 * (github.com/kench/helios-console), a static single-page app.
 *
 * The built site (`console/build/client`, from the helios-console submodule)
 * goes into a private S3 bucket and is served by CloudFront at its default
 * domain, or at a custom domain once one is set ({@link ConsoleDomain}). Every
 * path that isn't a file gets `index.html`, so the app's own routes work.
 *
 * Files under `assets/` have content hashes in their names, so browsers keep
 * them for a year; everything else (`index.html`) is checked on every load,
 * and each deploy invalidates the distribution.
 *
 * The content security policy allows only the console's own origin, plus the
 * inline scripts React Router writes into `index.html`, by hash
 * ({@link inlineScriptHashes}).
 */

import {createHash} from 'node:crypto';

import {Duration, NestedStack, RemovalPolicy} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {Certificate} from 'aws-cdk-lib/aws-certificatemanager';
import {
  CachePolicy,
  Distribution,
  HeadersFrameOption,
  HeadersReferrerPolicy,
  HttpVersion,
  PriceClass,
  ResponseHeadersPolicy,
  ViewerProtocolPolicy,
} from 'aws-cdk-lib/aws-cloudfront';
import {S3BucketOrigin} from 'aws-cdk-lib/aws-cloudfront-origins';
import {
  BlockPublicAccess,
  Bucket,
  BucketEncryption,
  BucketNamespace,
  ObjectOwnership,
} from 'aws-cdk-lib/aws-s3';
import {BucketDeployment, CacheControl} from 'aws-cdk-lib/aws-s3-deployment';
import type {ISource} from 'aws-cdk-lib/aws-s3-deployment';
import type {Construct} from 'constructs';

/** The prefix of the console bucket's name. */
export const CONSOLE_BUCKET_PREFIX = 'helios-console';

/** Where the build puts files whose names change with their content. */
export const HASHED_PREFIX = 'assets/';

/** How long browsers keep a hashed file. */
export const HASHED_MAX_AGE = Duration.days(365);

/** The console's development server (`npm run dev`), for sign-in and the API. */
export const CONSOLE_DEV_ORIGIN = 'http://localhost:5173';

/** CloudFront's limit on a response headers policy's content security policy. */
export const MAX_CSP_LENGTH = 1783;

/**
 * A custom domain for the console. The certificate is made by hand in
 * us-east-1 (CloudFront only uses certificates from there) and must cover
 * every name; the DNS records pointing the names at the distribution are
 * added by hand too.
 */
export interface ConsoleDomain {
  /** The names the console answers to, e.g. `console.example.com`. */
  readonly names: string[];
  /** The ACM certificate's ARN, in us-east-1. */
  readonly certificateArn: string;
}

/** Settings for {@link ConsoleStack}. */
export interface ConsoleStackProps extends NestedStackProps {
  /** The built site: the contents of `build/client`. */
  readonly site: ISource;
  /** `index.html` as built, for the hashes of its inline scripts. */
  readonly indexHtml: string;
  /** A custom domain; CloudFront's default domain only, if absent. */
  readonly domain?: ConsoleDomain;
}

/** The nested stack for the console's bucket, distribution and deploys. */
export class ConsoleStack extends NestedStack {
  /** Holds the built site. */
  readonly bucket: Bucket;

  /** Serves it over HTTPS. */
  readonly distribution: Distribution;

  constructor(scope: Construct, id: string, props: ConsoleStackProps) {
    super(scope, id, {
      description: 'Helios console: S3, CloudFront and the site',
      ...props,
    });

    this.bucket = new Bucket(this, 'Bucket', {
      bucketNamePrefix: CONSOLE_BUCKET_PREFIX,
      bucketNamespace: BucketNamespace.ACCOUNT_REGIONAL,
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
      objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      minimumTLSVersion: 1.2,
      // Only built files, replaced by every deploy.
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const headers = new ResponseHeadersPolicy(this, 'Headers', {
      comment: 'Helios console: security headers',
      securityHeadersBehavior: {
        contentSecurityPolicy: {
          contentSecurityPolicy: contentSecurityPolicy(
            inlineScriptHashes(props.indexHtml),
          ),
          override: true,
        },
        strictTransportSecurity: {
          accessControlMaxAge: Duration.days(365),
          includeSubdomains: false,
          override: true,
        },
        contentTypeOptions: {override: true},
        frameOptions: {frameOption: HeadersFrameOption.DENY, override: true},
        referrerPolicy: {
          referrerPolicy: HeadersReferrerPolicy.NO_REFERRER,
          override: true,
        },
      },
    });

    const domain = props.domain;
    this.distribution = new Distribution(this, 'Distribution', {
      comment: 'Helios console',
      defaultRootObject: 'index.html',
      defaultBehavior: {
        origin: S3BucketOrigin.withOriginAccessControl(this.bucket),
        viewerProtocolPolicy: ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: CachePolicy.CACHING_OPTIMIZED,
        responseHeadersPolicy: headers,
      },
      // The app's own routes (/devices/...) aren't files: S3 answers 403 to
      // CloudFront for a missing object, so both become index.html.
      errorResponses: [403, 404].map(httpStatus => ({
        httpStatus,
        responseHttpStatus: 200,
        responsePagePath: '/index.html',
        ttl: Duration.seconds(0),
      })),
      httpVersion: HttpVersion.HTTP2_AND_3,
      priceClass: PriceClass.PRICE_CLASS_100,
      ...(domain && {
        domainNames: domain.names,
        certificate: Certificate.fromCertificateArn(
          this,
          'Certificate',
          domain.certificateArn,
        ),
      }),
    });

    // Hashed files first, so the new index.html never names a missing one.
    // Each deployment leaves the other's files alone (`exclude` also keeps
    // them from being pruned).
    const hashed = new BucketDeployment(this, 'HashedFiles', {
      sources: [props.site],
      destinationBucket: this.bucket,
      exclude: ['*'],
      include: [`${HASHED_PREFIX}*`],
      cacheControl: [
        CacheControl.setPublic(),
        CacheControl.maxAge(HASHED_MAX_AGE),
        CacheControl.immutable(),
      ],
    });
    const pages = new BucketDeployment(this, 'Pages', {
      sources: [props.site],
      destinationBucket: this.bucket,
      exclude: [`${HASHED_PREFIX}*`],
      cacheControl: [CacheControl.noCache()],
      distribution: this.distribution,
      distributionPaths: ['/*'],
    });
    pages.node.addDependency(hashed);
  }

  /** The console's URL at CloudFront's default domain. */
  get url(): string {
    return `${this.origin}/`;
  }

  /** The console's origin at CloudFront's default domain, for CORS. */
  get origin(): string {
    return `https://${this.distribution.distributionDomainName}`;
  }
}

/**
 * The CSP source expression (`'sha256-…'`) of each inline script in `html`:
 * React Router's single-page build writes its bootstrap into `index.html`.
 * Scripts with a `src` are covered by `'self'`.
 */
export function inlineScriptHashes(html: string): string[] {
  const hashes = new Set<string>();
  for (const match of html.matchAll(
    /<script(\s[^>]*)?>([\s\S]*?)<\/script>/gi,
  )) {
    const attributes = match[1] ?? '';
    const body = match[2] ?? '';
    if (/\ssrc\s*=/i.test(attributes) || body === '') {
      continue;
    }
    const digest = createHash('sha256').update(body, 'utf8').digest('base64');
    hashes.add(`'sha256-${digest}'`);
  }
  return [...hashes];
}

/**
 * The console's content security policy: its own origin only, and the
 * inline scripts in `scriptHashes`.
 */
export function contentSecurityPolicy(scriptHashes: string[]): string {
  const policy = [
    "default-src 'self'",
    `script-src 'self' ${scriptHashes.join(' ')}`.trim(),
    "style-src 'self'",
    "img-src 'self' data:",
    "connect-src 'self'",
    "font-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ');
  if (policy.length > MAX_CSP_LENGTH) {
    throw new Error(
      `the console's content security policy is ${policy.length} characters, over CloudFront's ${MAX_CSP_LENGTH}`,
    );
  }
  return policy;
}
