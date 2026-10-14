plugins {
    java
    id("io.gatling.gradle") version "3.9.5.6"
    id("com.google.protobuf")
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Same gRPC/protobuf versions as the service.
val grpcVersion = "1.63.0"
val protobufVersion = "3.25.3"

sourceSets {
    named("gatling") {
        proto {
            // Generate client stubs from the service's own contract.
            srcDir("../src/main/proto")
        }
    }
}

dependencies {
    // Open-source gRPC support for Gatling (github.com/phisgr/gatling-grpc, Apache-2.0). The official
    // io.gatling:gatling-grpc-java plugin is a trial capped at 5 virtual users.
    gatlingImplementation("com.github.phisgr:gatling-grpc-kt:0.17.0")
    gatlingImplementation("io.grpc:grpc-protobuf:$grpcVersion")
    gatlingImplementation("io.grpc:grpc-stub:$grpcVersion")
    gatlingImplementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    gatlingImplementation("io.grpc:grpc-netty:$grpcVersion") // align with the plugin's transport
    gatlingImplementation("com.google.protobuf:protobuf-java:$protobufVersion")
    gatlingCompileOnly("javax.annotation:javax.annotation-api:1.3.2")
    gatlingImplementation("org.postgresql:postgresql:42.7.7")
    gatlingImplementation("io.lettuce:lettuce-core:6.3.2.RELEASE")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

// Pass load settings through, e.g. ./gradlew :load-test:gatlingRun --simulation ... -Prate=300
val loadSettings = listOf("grpc.host", "grpc.port", "rate", "duration", "warmup", "rankShare", "users", "rounds", "pool", "channels")

tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    systemProperties =
        loadSettings.filter { project.hasProperty(it) }.associateWith { project.property(it).toString() } +
        mapOf("load.dir" to layout.buildDirectory.get().asFile.absolutePath)
}

tasks.register<JavaExec>("verifyWrites") {
    group = "verification"
    description = "After ConcurrentWriteSimulation: checks Postgres and Redis to prove zero lost writes."
    classpath = sourceSets["gatling"].runtimeClasspath
    mainClass = "com.asmipahwa.leaderboard.load.WriteVerifier"
    systemProperty("load.dir", layout.buildDirectory.get().asFile.absolutePath)
}
