# AGENTS.md

helios-cloud holds the AWS side of Helios: the CDK stack `HeliosCloud` (TypeScript, in `cdk/`)
and the bootstrap service's Lambdas (Java 25, in `lambda/`). The design is in Notion: "Helios -
Project Status and Handoff", the Bootstrap API page, and LLD 2 (cloud infrastructure). `README.md`
describes each nested stack and how to build and deploy. `cdk/AGENTS.md` has the CDK-specific
instructions (read the construct READMEs first, style, checks).

## What's deployed

`HeliosCloud` in account 857247606239, us-west-2, with nested stacks:

| Stack | File | What |
| --- | --- | --- |
| DeviceIdentity | `cdk/lib/device_identity_stack.ts` | `helios-device-policy`, the device role and `helios-device-role-alias` |
| Provisioning | `cdk/lib/provisioning_stack.ts` | `helios-provisioning` role and `helios-provisioner` user |
| Operator | `cdk/lib/operator_stack.ts` | `helios-operator` role for device commands |
| Bootstrap | `cdk/lib/bootstrap_stack.ts` | Device, admin and control APIs (one REST API, java25 ARM64 Lambdas, DynamoDB) |
| AptRepository | `cdk/lib/apt_repository_stack.ts` | Helios APT repository (S3 + CloudFront) and `helios-apt-publisher` for helios's `v*` tags |

## Check your work

```sh
cd cdk && npm ci && npm run build && npm run lint && npm test && npx cdk synth
cd lambda && ./gradlew check     # needs JDK 25 and the contract submodule
```

`cdk synth` needs the Lambda zips (`./gradlew lambdaZip`). CI runs the CDK checks, a synth and
the Java build; at least 90% line coverage in both.

## Deploying

Only through `.github/workflows/deploy.yml`, started by hand from `main`
(`gh workflow run deploy.yml --ref main`), and only when the owner asks. Never run
`cdk deploy` locally. The workflow assumes the shared `GitHubActionsRole` (owned by kench/Kenley)
through GitHub OIDC with this repository's immutable subject.

## Rules

- Conventional Commits for commits and PR titles; squash merges.
- Temporary simplifications that must not be reversed silently: the default execute-api domain
  (no custom domain), PINs in DynamoDB (no KMS key), no WAF, Secrets Manager for device secrets,
  and `helios-provisioning`'s grant to send commands (remove once operators use `helios-operator`).
- DynamoDB tables: `TableClass.STANDARD`, on-demand billing, no point-in-time recovery, retained.
- S3 buckets use the account-regional namespace (`bucketNamePrefix` + `BucketNamespace.ACCOUNT_REGIONAL`).
- TypeScript: Google style (gts), named exports, `snake_case` files. Java: Google Java Style,
  current stable Gradle, JUnit, JaCoCo and Mockito.
- IAM grants stay least-privilege and are asserted exactly in the tests.
