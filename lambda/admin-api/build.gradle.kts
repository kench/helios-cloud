// The admin API Lambda (Bootstrap API, section 4): registering, reading,
// enabling or disabling, and deleting device records. IAM-authorized.

dependencies {
    implementation(project(":common"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb) {
        // As in device-api: the JDK's HTTP client only.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
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
}

val lambdaZip = tasks.register<Zip>("lambdaZip") {
    archiveFileName = "admin-api.zip"
    destinationDirectory = layout.buildDirectory.dir("lambda")
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}
tasks.assemble { dependsOn(lambdaZip) }
