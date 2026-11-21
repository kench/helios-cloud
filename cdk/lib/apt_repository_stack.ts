/**
 * @fileoverview The Helios APT repository: where the Debian devices (the relay
 * and transcoder boards) get Helios releases, and where the helios repo's
 * release workflow publishes them.
 *
 * The repository is a private S3 bucket behind a CloudFront distribution,
 * public over HTTPS at the distribution's default domain. Packages are signed
 * by the release workflow (APT checks the signed `InRelease` file), so the
 * transport doesn't need to be trusted. Only the helios repo's workflow, run
 * for a `v*` tag, can publish: it assumes the publisher role through GitHub's
 * OIDC provider, writes `pool/`, `dists/` and `keys/`, and invalidates the
 * changed indices.
 *
 * Layout:
 *
 *  - `pool/main/h/helios/*.deb`: packages, never changed once uploaded.
 *  - `dists/stable/...`: the indices and `InRelease`, replaced at each release.
 *  - `keys/helios-archive-keyring.asc`: the public signing key.
 */

import {Duration, NestedStack, RemovalPolicy} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import {
  CachePolicy,
  Distribution,
  PriceClass,
  ViewerProtocolPolicy,
} from 'aws-cdk-lib/aws-cloudfront';
import {S3BucketOrigin} from 'aws-cdk-lib/aws-cloudfront-origins';
import {
  OpenIdConnectPrincipal,
  OpenIdConnectProvider,
  PolicyStatement,
  Role,
} from 'aws-cdk-lib/aws-iam';
import {
  BlockPublicAccess,
  Bucket,
  BucketEncryption,
  BucketNamespace,
  ObjectOwnership,
} from 'aws-cdk-lib/aws-s3';
import type {Construct} from 'constructs';

/** The bucket name's prefix; S3 adds `-<account>-<region>-an`. */
export const APT_BUCKET_PREFIX = 'helios-apt';

/** The publisher role's name, for the helios repo's release workflow. */
export const APT_PUBLISHER_ROLE_NAME = 'helios-apt-publisher';

/** The key prefixes the publisher may write. */
export const APT_PUBLISHED_PREFIXES = ['pool/', 'dists/', 'keys/'];

/** The GitHub repository that publishes, with GitHub's immutable IDs. */
export const RELEASE_REPOSITORY = {
  /** `kench`, user ID 34564. */
  owner: 'kench@34564',
  /** `helios`, repository ID 1464316682. */
  name: 'helios@1464316682',
};

/** The OIDC subject of the helios repo's workflow runs for `v*` tags. */
export const RELEASE_SUBJECT =
  `repo:${RELEASE_REPOSITORY.owner}/${RELEASE_REPOSITORY.name}` +
  ':ref:refs/tags/v*';

/** How long replaced or deleted object versions are kept, to undo a bad publish. */
const NONCURRENT_VERSION_DAYS = 30;

/** The nested stack for the APT repository. */
export class AptRepositoryStack extends NestedStack {
  /** The bucket that holds the repository. */
  readonly bucket: Bucket;

  /** The public HTTPS front of the repository. */
  readonly distribution: Distribution;

  /** The role the release workflow assumes to publish. */
  readonly publisherRole: Role;

  constructor(scope: Construct, id: string, props?: NestedStackProps) {
    super(scope, id, {
      description: 'Helios APT repository: S3, CloudFront and the publisher',
      ...props,
    });

    this.bucket = new Bucket(this, 'Bucket', {
      bucketNamePrefix: APT_BUCKET_PREFIX,
      bucketNamespace: BucketNamespace.ACCOUNT_REGIONAL,
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
      objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      minimumTLSVersion: 1.2,
      // Releases overwrite the indices; earlier versions are kept for a while
      // so a bad publish can be rolled back.
      versioned: true,
      lifecycleRules: [
        {
          noncurrentVersionExpiration: Duration.days(NONCURRENT_VERSION_DAYS),
        },
      ],
      removalPolicy: RemovalPolicy.RETAIN,
    });

    this.distribution = new Distribution(this, 'Distribution', {
      comment: 'Helios APT repository',
      defaultBehavior: {
        origin: S3BucketOrigin.withOriginAccessControl(this.bucket),
        viewerProtocolPolicy: ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        // Packages never change, and each release invalidates `dists/*`.
        cachePolicy: CachePolicy.CACHING_OPTIMIZED,
      },
      // North America and Europe: where the devices are.
      priceClass: PriceClass.PRICE_CLASS_100,
    });

    const github = OpenIdConnectProvider.fromOpenIdConnectProviderArn(
      this,
      'GitHub',
      this.formatArn({
        service: 'iam',
        region: '',
        resource: 'oidc-provider',
        resourceName: 'token.actions.githubusercontent.com',
      }),
    );
    this.publisherRole = new Role(this, 'PublisherRole', {
      roleName: APT_PUBLISHER_ROLE_NAME,
      assumedBy: new OpenIdConnectPrincipal(github, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
        },
        StringLike: {
          'token.actions.githubusercontent.com:sub': RELEASE_SUBJECT,
        },
      }),
      description: 'Publish Helios releases to the APT repository',
      maxSessionDuration: Duration.hours(1),
    });
    this.publisherRole.addToPolicy(
      new PolicyStatement({
        actions: ['s3:ListBucket'],
        resources: [this.bucket.bucketArn],
        conditions: {
          StringLike: {
            's3:prefix': APT_PUBLISHED_PREFIXES.map(prefix => `${prefix}*`),
          },
        },
      }),
    );
    this.publisherRole.addToPolicy(
      new PolicyStatement({
        actions: ['s3:GetObject', 's3:PutObject'],
        resources: APT_PUBLISHED_PREFIXES.map(prefix =>
          this.bucket.arnForObjects(`${prefix}*`),
        ),
      }),
    );
    this.distribution.grantCreateInvalidation(this.publisherRole);
  }

  /** The repository's base URL, for APT sources. */
  get repositoryUrl(): string {
    return `https://${this.distribution.distributionDomainName}`;
  }
}
