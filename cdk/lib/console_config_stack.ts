/**
 * @fileoverview The Helios console's settings, `config.json` beside the site:
 * where the Control API for browsers is and how to sign in. The console reads
 * it when it starts, so the build has no URLs or IDs in it.
 *
 * Its own nested stack, because it needs both the console's bucket and the
 * Control API's URL, and the bootstrap stack already depends on the console
 * stack (for its CORS origin).
 */

import {NestedStack} from 'aws-cdk-lib';
import type {NestedStackProps} from 'aws-cdk-lib';
import type {IDistribution} from 'aws-cdk-lib/aws-cloudfront';
import type {IBucket} from 'aws-cdk-lib/aws-s3';
import {
  BucketDeployment,
  CacheControl,
  Source,
} from 'aws-cdk-lib/aws-s3-deployment';
import type {Construct} from 'constructs';

import {CONSOLE_CONFIG_OBJECT} from './console_stack';

/** What `config.json` holds. */
export interface ConsoleConfig {
  /** The Control API for browsers, without a trailing slash. */
  readonly apiUrl: string;
  /** Sign-in through the identity provider (OAuth 2.0 with PKCE). */
  readonly auth: {
    /** The managed login's domain, e.g. `https://x.auth.<region>.amazoncognito.com`. */
    readonly domain: string;
    /** The console's app client (public, no secret). */
    readonly clientId: string;
    /** RFC 8707's `resource`: the Control API's audience. */
    readonly resource: string;
    /** The scopes to ask for. */
    readonly scopes: string[];
  };
}

/** Settings for {@link ConsoleConfigStack}. */
export interface ConsoleConfigStackProps extends NestedStackProps {
  /** The console's bucket. */
  readonly bucket: IBucket;
  /** Its distribution, which forgets the old file after each deploy. */
  readonly distribution: IDistribution;
  /** The settings. */
  readonly config: ConsoleConfig;
}

/** The nested stack that writes the console's `config.json`. */
export class ConsoleConfigStack extends NestedStack {
  constructor(scope: Construct, id: string, props: ConsoleConfigStackProps) {
    super(scope, id, {
      description: 'Helios console: its config.json (API and sign-in)',
      ...props,
    });

    new BucketDeployment(this, 'Config', {
      sources: [Source.jsonData(CONSOLE_CONFIG_OBJECT, props.config)],
      destinationBucket: props.bucket,
      // Only this file: the site's own deployments manage the rest.
      prune: false,
      cacheControl: [CacheControl.noCache()],
      distribution: props.distribution,
      distributionPaths: [`/${CONSOLE_CONFIG_OBJECT}`],
    });
  }
}
