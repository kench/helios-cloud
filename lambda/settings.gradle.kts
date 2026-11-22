// The Helios Lambdas (Bootstrap API, section 7): Java 25, built with Gradle.
rootProject.name = "helios-cloud-lambda"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Types generated from the Smithy model in ../contract (helios-contract).
include("model")

// Shared by the Lambdas: restJson1 over API Gateway, IDs, log lines, challenge keys.
include("common")

// The device API Lambda: Bootstrap and AnswerChallenge (Bootstrap API, section 3).
include("device-api")

// The admin API Lambda: device registration and management (Bootstrap API, section 4).
include("admin-api")

// The control API Lambda: devices and their streams for operators and agents (HLD section 7.3).
include("control-api")
