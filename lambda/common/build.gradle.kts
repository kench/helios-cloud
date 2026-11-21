// Shared by the Lambdas: restJson1 over API Gateway proxy events, IDs, JSON
// log lines and challenge keys.

plugins {
    `java-library`
}

dependencies {
    api(project(":model"))
    api(libs.aws.lambda.core)
    api(libs.aws.lambda.events)

    testImplementation(libs.smithy.model)
}
