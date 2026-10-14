plugins {
    // Lets Gradle download a Java 21 toolchain when the machine running the build doesn't have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "leaderboard-service"
