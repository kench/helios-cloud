# AGENTS.md

This is an AWS CDK app written in TypeScript. The app entry point is the file in
`bin/` that creates the `App` (`bin/helios_cloud.ts`); the stack is defined in
`lib/` (`lib/helios_cloud_stack.ts`). `cdk.json` tells the CDK Toolkit how to run the app.

## Read the construct library docs before writing code

AWS CDK service documentation ships inside the aws-cdk-lib package. After you
install dependencies with `npm install` (so `node_modules/aws-cdk-lib` exists)
and before you write your CDK constructs, find the service READMEs once:

```
find node_modules/aws-cdk-lib -name README.md
```

For each AWS service you use in this app, read that service's module README (for
example, `node_modules/aws-cdk-lib/aws-s3/README.md`) first and follow its
documented construct usage and patterns. Not every service has a README; a
missing one is not an error. Read the relevant READMEs before writing the
constructs for each service.

Before reading a service README, list its section headings with
`grep -n '^#' node_modules/aws-cdk-lib/<module>/README.md`, then read only the
sections for the constructs you use, for example with
`sed -n '<start>,<end>p' node_modules/aws-cdk-lib/<module>/README.md`. Do not
read a whole README into context or re-read a section you have already read.

## Check your work

- `npm run build` — type-check the project.
- `npm run lint` — check Google TypeScript style with gts (`npm run fix` fixes formatting).
- `npm run test` — run the jest unit tests (`test/*_test.ts`) with coverage; at least 90% of lines.
- `npx cdk synth` — synthesize the CloudFormation template.
- `npx cdk diff` — compare against the deployed stack.
- `npx cdk deploy` changes a real AWS account. Ask the user first.

## More resources

- [AWS CDK Reference](https://docs.aws.amazon.com/cdk/api/)

## Style

Follow the Google TypeScript Style Guide, with the TypeScript handbook's
declaration-file Do's and Don'ts where Google's guide is silent (Helios HLD,
section 12): named exports only, `snake_case` file names, single quotes, JSDoc
on exported symbols, no `any`. TypeScript stays on 6.0 until typescript-eslint
(used by gts) supports TypeScript 7.
