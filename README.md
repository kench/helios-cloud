# helios-cloud

The cloud side of Helios: a CDK app for the AWS resources Helios devices use, and the bootstrap
service's Lambdas. The design is in the Helios design pages in Notion: the High-Level
Design, LLD 2 (section 8, cloud infrastructure) and the Bootstrap API (section 7).
Current status: *Helios - Project Status and Handoff*. `AGENTS.md` summarizes the stacks, checks,
deploy rules and conventions for coding agents.

The `HeliosCloud` stack holds one nested stack for each group of resources:

- `DeviceIdentity`: the AWS IoT policy for device certificates (`helios-device-policy`), and the
  IAM role and role alias (`helios-device-role-alias`) that devices get temporary AWS credentials
  through. Each device can use only its own client ID, topics and shadows.
- `Provisioning`: the `helios-provisioning` role, which can only create AWS IoT things and
  certificates and attach them (to a thing and to an IoT policy), and the `helios-provisioner`
  IAM user, which can only assume that role (and `helios-operator`) and sign in to the AWS CLI
  with `aws login`. The user's console password and MFA device are set in the IAM console, not in
  CDK, and it has no access keys. **Temporarily**, `helios-provisioning` can also send commands
  (as `helios-operator` can), so a freshly provisioned scanner can be checked with `helios.ping`
  from the same profile; that grant goes once operators use `helios-operator`.
- `Operator`: the `helios-operator` role, which can publish Helios commands
  (`helios/<thing>/cmd/<module>`) and read the responses (`helios/<thing>/rsp/<module>`) over
  MQTT, connecting with a client ID that starts with `helios-operator-` (LLD 2, section 7.2).

- `Bootstrap`: the bootstrap service (Bootstrap API, section 7). The device API Lambda (`java25`,
  ARM64), the admin API Lambda and the control API Lambda (HLD section 7.3) behind one regional
  REST API, with the `devices`, `mac_index` and `challenges` DynamoDB
  tables. The API is dual-stack (IPv4 and IPv6) on its `execute-api` name; Helios 0.10.0 and later
  connect with Happy Eyeballs. For now it uses the API's default `execute-api` domain (stage `dev`), keeps PINs in the
  `devices` table under DynamoDB's encryption at rest, and is protected only by stage throttling
  (10 requests a second, bursts of 20). The stack output `BootstrapUrl` is the value for Helios's
  `bootstrap.url`. The device routes are open, except `GET /v1/device/egress` (LLD 3, section
  3.1): an IAM-signed mock integration that only the device role may call, answering
  `{"address": <the caller's address as API Gateway saw it>, "atMs": <request time>}` so
  Helios learns each uplink's public address; the admin routes (`/v1/admin/devices…`) need a
  SigV4 signature, and `helios-provisioning` may call `RegisterDevice` and `GetDevice` only. The
  control routes (`/v1/control/…`) need a SigV4 signature too, and `helios-operator` may call
  them all.
- `AptRepository`: the public APT repository the Debian devices (relay and transcoder) install
  Helios from. A private, versioned S3 bucket in the account-regional namespace
  (`helios-apt-<account>-<region>-an`; replaced versions kept 30 days) behind a CloudFront
  distribution with origin access control, on its default domain over HTTPS (output
  `AptRepositoryUrl`). The helios repo's release workflow, run for a `v*` tag, assumes
  `helios-apt-publisher` through GitHub's OIDC provider (immutable subject
  `repo:kench@34564/helios@1464316682:ref:refs/tags/v*`). It may only read and write `pool/`,
  `dists/` and `keys/` (no deletes) and invalidate the distribution. Packages are signed by that
  workflow, so APT verifies them whatever the transport. The outputs `AptBucketName`,
  `AptDistributionId` and `AptPublisherRoleArn` are for the workflow. A second publisher,
  `helios-packages-publisher` (output `AptPackagesPublisherRoleArn`), is for kench/helios-packages'
  release tags (`<package>/<version>`, subject
  `repo:kench@34564/helios-packages@1466772735:ref:refs/tags/*/*`): it may only read and write its
  own `trixie` suite (`pool/trixie/`, `dists/trixie/`) and its key
  (`keys/helios-packages-archive-keyring.asc`), which has its own signing key.

## Layout

```
contract/             git submodule: helios-contract (the Smithy model), pinned to a release tag
lambda/               the Lambdas (Java 25, Gradle)
  model/                Java types generated from the contract with Smithy Java
  common/               shared by the Lambdas: restJson1 over API Gateway, IDs, log lines, keys
  device-api/           the device API Lambda: Bootstrap and AnswerChallenge
  admin-api/            the admin API Lambda: RegisterDevice, GetDevice, SetDeviceStatus, DeleteDevice
  control-api/          the control API Lambda: devices, inputs, profiles and streams for operators
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
npx cdk synth   # synthesize the CloudFormation template into cdk.out/ (needs the Lambda zip below)
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

`lambda/model` generates Java types for the Bootstrap and Control APIs from the Smithy model in
`contract/` with Smithy Java's type code generation (`smithy-build.json`): the Bootstrap API's in
`tv.kenley.helios.cloud.model`, and the Control API's (namespace `tv.kenley.helios.control`,
projection `control`) in `tv.kenley.helios.cloud.control.model`, since both have shapes such as
`GetDeviceInput`. Each projection has a selector; their `SchemaIndex` service files are merged. Its test reads every protocol test
in the model and checks that each body round-trips through the generated types. Generated code
is excluded from Checkstyle, Spotless and coverage.

`cdk synth` and `cdk deploy` need the Lambda packages first: `./gradlew lambdaZip` in `lambda/`. CI's Synth job and the deploy workflow build it.
Archives are reproducible, so an unchanged Lambda keeps its asset hash and isn't redeployed.

`lambda/device-api` is the device API Lambda (Bootstrap API, section 3). Its handler is
`tv.kenley.helios.cloud.device.DeviceApiHandler::handleRequest`, behind an API Gateway REST API
with Lambda proxy integration. `./gradlew :device-api:lambdaZip` builds the deployment package,
`device-api/build/lambda/device-api.zip`. It reads its DynamoDB tables and default AWS settings
from environment variables (`Settings.java`), and the item layout is documented in
`DeviceStore.java`. Tests use Mockito, loaded as a Java agent.

`lambda/admin-api` is the admin API Lambda (Bootstrap API, section 4), for provisioning scripts
and operators: `RegisterDevice`, `GetDevice`, `SetDeviceStatus` and `DeleteDevice` so far. Its
handler is `tv.kenley.helios.cloud.admin.AdminApiHandler::handleRequest`, and
`./gradlew :admin-api:lambdaZip` builds `admin-api/build/lambda/admin-api.zip`. Registration and
deletion are DynamoDB transactions over the device record and its `mac_index` entries, so a MAC
never belongs to two records. Responses never include the PIN, and each call writes an audit line
with the caller's IAM ARN.

`lambda/control-api` is the control API Lambda (HLD section 7.3), under `/v1/control`, IAM-signed;
callers assume `helios-operator` (`grantControl`). Its handler is
`tv.kenley.helios.cloud.control.ControlApiHandler::handleRequest`, and
`./gradlew :control-api:lambdaZip` builds `control-api/build/lambda/control-api.zip`. It reads the
devices table (never writing it, and never reading PINs), reads the `gps`, `helios`, `net` and `stream` shadows,
and changes streams by writing the `stream` shadow's desired state (HLD section 7.3.7 lists the
fields); the device applies them and reports back. Changes answer 202 with the shadow version.
Destinations are SRT or SRTLA URLs only, checked by `Destinations.java` and stored in a normal
form. Each call writes an audit line with the caller's IAM ARN, and a destination change also
records the new destination. `GetStreamStatus` (a command with a response) isn't routed yet.

Location (LLD 10): `GetDeviceLocation` (`GET …/devices/{deviceId}/location`) reads the `gps`
shadow into the contract's fix, last fix, receivers and satellites (`GpsShadow.java`); 409
`DeviceStateUnavailable` when the device has never reported gps. It returns the device's exact
position, so nothing logs it: the audit line has the operation and result only.

Wi-Fi networks (LLD 3, section 10): `ListWifiNetworks` reads the `net` shadow; `SetWifiNetwork`
and `DeleteWifiNetwork` are **AWS IoT command executions**, not shadow writes (LLD 2, section
7.3): the stack defines the commands `helios-net-wifi-set-v1` and `helios-net-wifi-remove-v1` (versioned, since AWS can't change a command's payload in place and holds a deleted ID for 12 hours; one payload
template: `{"module", "name", "timeout_s", "args"}`, `args` base64 of the arguments' JSON), the
Lambda starts an execution (10 s timeout) and polls for the device's answer, and a device that
doesn't answer gives `DeviceUnreachable`. A passphrase is **sealed to the device** first, because
AWS keeps executions: HPKE (RFC 9180; DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM) to the
`secrets_key` in its `helios` shadow, `info` `helios secret v1 <thing>`, AAD
`wifi.passphrase <ssid>` (`Sealing.java`, JDK primitives only, checked against RFC 9180's
vectors). The plaintext is never stored or logged; the audit line has the SSID. The device policy
allows the `$aws/commands/things/<thing>/executions/…` topics, and the Lambda may start and read
executions of only those two commands.

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
