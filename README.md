# helios-cloud

The cloud side of Helios: a CDK app for the AWS resources Helios devices use, and later the
bootstrap service's Lambdas. The design is in the Helios design pages in Notion: the High-Level
Design, LLD 2 (section 8, cloud infrastructure) and the Bootstrap API (section 7).

The `HeliosCloud` stack holds one nested stack for each group of resources:

- `DeviceIdentity`: the AWS IoT policy for device certificates (`helios-device-policy`), and the
  IAM role and role alias (`helios-device-role-alias`) that devices get temporary AWS credentials
  through. Each device can use only its own client ID, topics and shadows.
- `Provisioning`: the `helios-provisioning` role, which can only create AWS IoT things and
  certificates and attach them (to a thing and to an IoT policy), and the `helios-provisioner`
  IAM user, which can only assume that role and sign in to the AWS CLI with `aws login`. The
  user's console password and MFA device are set in the IAM console, not in CDK, and it has no
  access keys.

The bootstrap service comes in a later change.

## Layout

```
contract/             git submodule: helios-contract (the Smithy model), pinned to a release tag
lambda/               the Lambdas (Java 25, Gradle)
  model/                Java types generated from the contract with Smithy Java
  device-api/           the device API Lambda: Bootstrap and AnswerChallenge
cdk/                  CDK app (TypeScript), generated with `cdk init app --language typescript`
  bin/helios_cloud.ts   entry point: the HeliosCloud stack in us-west-2
  lib/                  the stack and its nested stacks
  test/                 Jest tests (*_test.ts) on the synthesized template
  AGENTS.md             notes for coding agents (from cdk init, adapted)
.github/workflows/    CI (ci.yml) and the manual deploy (deploy.yml)
```

## Development

Needs Node.js 26 (`cdk/.nvmrc`).

```sh
cd cdk
npm ci
npm run build   # type-check (tsc)
npm run lint    # gts: Google TypeScript style
npm run fix     # apply gts formatting fixes
npm test        # Jest with coverage; fails below 90% line coverage
npx cdk synth   # synthesize the CloudFormation template into cdk.out/
```

`npx cdk deploy` changes the real AWS account. Don't run it unless a change is meant to be
deployed.

## Deploying

Deploys run from GitHub Actions (`.github/workflows/deploy.yml`), only when started by hand from
`main`. Merging a PR doesn't deploy it.

```sh
gh workflow run deploy.yml --ref main
gh run watch
```

The job type-checks and tests, prints `cdk diff`, then runs `cdk deploy`. It signs in to AWS with
GitHub's OIDC token as `GitHubActionsRole`, the role `Kenley/CDK` deploys with. That role's trust
policy must allow this repository's `main` branch. The repository uses GitHub's immutable OIDC
subject, which has the owner and repository IDs instead of only their names, so the trust
policy's `token.actions.githubusercontent.com:sub` value is:

```
repo:kench@34564/helios-cloud@1463717924:ref:refs/heads/main
```

`gh api repos/kench/helios-cloud/actions/oidc/customization/sub` shows the prefix.

### Java

Needs a JDK 25 and the `contract` submodule (`git submodule update --init`, or clone with
`--recurse-submodules`). Gradle 9.9.2 comes from the wrapper.

```sh
cd lambda
./gradlew check         # Spotless, Checkstyle, tests, at least 90% line coverage
./gradlew spotlessApply # apply google-java-format
```

`lambda/model` generates Java types for the Bootstrap API from the Smithy model in `contract/`
with Smithy Java's type code generation (`smithy-build.json`). Its test reads every protocol test
in the model and checks that each body round-trips through the generated types. Generated code
is excluded from Checkstyle, Spotless and coverage.

`lambda/device-api` is the device API Lambda (Bootstrap API, section 3). Its handler is
`tv.kenley.helios.cloud.device.DeviceApiHandler::handleRequest`, behind an API Gateway REST API
with Lambda proxy integration. `./gradlew :device-api:lambdaZip` builds the deployment package,
`device-api/build/lambda/device-api.zip`. It reads its DynamoDB tables and default AWS settings
from environment variables (`Settings.java`), and the item layout is documented in
`DeviceStore.java`. Tests use Mockito, loaded as a Java agent.

## Standards

- TypeScript follows the [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html)
  and the TypeScript handbook's
  [Do's and Don'ts](https://www.typescriptlang.org/docs/handbook/declaration-files/do-s-and-don-ts.html),
  with Google's guide taking precedence. gts checks it in CI. File names are `snake_case`.
- Java (the bootstrap Lambdas, later) follows the
  [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html), on Java 25.
- At least 90% line coverage.
- Commit messages and PR titles use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)
  (scopes `cdk`, `lambda`, `ci`, `deps`). PRs are squash-merged.

TypeScript is pinned to 6.0: gts lints through typescript-eslint, which supports TypeScript up to
6.0, and ts-jest supports versions below 7.
