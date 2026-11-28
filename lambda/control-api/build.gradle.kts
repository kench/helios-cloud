// The control API Lambda (HLD section 7.3): devices, their inputs and profiles, and their
// streams, for operators and agents. IAM-authorized; reads the devices table and the devices'
// shadows, and writes the stream shadow's desired state.

dependencies {
    implementation(project(":common"))
    implementation(platform(libs.aws.sdk.bom))
    for (sdk in listOf(
        libs.aws.sdk.dynamodb,
        libs.aws.sdk.iot,
        libs.aws.sdk.iotdataplane,
        libs.aws.sdk.iotjobsdataplane,
    )) {
        implementation(sdk) {
            // As in device-api: the JDK's HTTP client only.
            exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
            exclude(group = "software.amazon.awssdk", module = "apache-client")
            exclude(group = "software.amazon.awssdk", module = "apache5-client")
        }
    }
    implementation(libs.aws.sdk.url.connection.client)

    testImplementation(libs.smithy.model)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

// Mockito as a Java agent, as in device-api.
val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent(libs.mockito.core) { isTransitive = false }
}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") })
    // The report vectors from the contract (helios-contract): how reports become read responses.
    val reportVectors = file("../../contract/test-vectors/control-reports-v1.json")
    // An input, so the tests run again when only the vectors change.
    inputs.file(reportVectors).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("helios.contract.reportVectors", reportVectors.absolutePath)
}

val lambdaZip = tasks.register<Zip>("lambdaZip") {
    archiveFileName = "control-api.zip"
    destinationDirectory = layout.buildDirectory.dir("lambda")
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}
tasks.assemble { dependsOn(lambdaZip) }
