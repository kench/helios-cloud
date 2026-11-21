// The device API Lambda (Bootstrap API, section 3): Bootstrap and
// AnswerChallenge behind API Gateway, with DynamoDB for device records,
// the MAC index and challenges.

dependencies {
    implementation(project(":model"))
    implementation(libs.aws.lambda.core)
    implementation(libs.aws.lambda.events)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb) {
        // The JDK's HTTP client (url-connection-client) is enough for one
        // request at a time, and keeps the package and cold start smaller.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation(libs.aws.sdk.url.connection.client)

    // Node, for reading the contract's challenge test vectors.
    testImplementation(libs.smithy.model)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

// Mockito's inline mock maker is a Java agent. Load it at startup rather than
// letting Mockito attach it at runtime, which JDK 21+ warns about.
val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent(libs.mockito.core) { isTransitive = false }
}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") })
    // The challenge test vectors from the contract (helios-contract).
    systemProperty(
        "helios.contract.vectors",
        file("../../contract/test-vectors/challenge-v1.json").absolutePath,
    )
}

// The deployment package: the code and its dependencies in lib/, the layout
// Lambda's Java runtimes expect. CDK uploads it.
val lambdaZip = tasks.register<Zip>("lambdaZip") {
    archiveFileName = "device-api.zip"
    destinationDirectory = layout.buildDirectory.dir("lambda")
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}
tasks.assemble { dependsOn(lambdaZip) }
