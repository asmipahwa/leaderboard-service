plugins {
    // Lets Gradle download a Java 21 toolchain when the machine running the build doesn't have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "leaderboard-service"

// Gatling load tests live in their own module so their dependencies never reach the service.
include("load-test")
