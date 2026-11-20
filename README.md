# helios-cloud

The cloud side of Helios: a CDK app for the AWS resources Helios devices use, and later the
bootstrap service's Lambdas. The design is in the Helios design pages in Notion: the High-Level
Design, LLD 2 (section 8, cloud infrastructure) and the Bootstrap API (section 7).

The stack is empty for now. The device identity resources (AWS IoT policy, IAM role and role
alias) and the bootstrap service come in later changes. Nothing is deployed from this repository
yet.

## Layout

```
cdk/                  CDK app (TypeScript), generated with `cdk init app --language typescript`
  bin/helios_cloud.ts   entry point: the HeliosCloud stack in us-west-2
  lib/                  constructs and the stack
  test/                 Jest tests (*_test.ts) on the synthesized template
  AGENTS.md             notes for coding agents (from cdk init, adapted)
.github/workflows/    CI
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
