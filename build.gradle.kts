import com.google.protobuf.gradle.id

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
}

java {
    // Build with JDK 21; the published bytecode targets Java 11.
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(platform(libs.grpc.bom))
    api(libs.grpc.api)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.protobuf.java)
    // validate/validate.proto: the generated Permio descriptors reference its
    // extensions, so its Java classes are needed at runtime. Only the stub
    // classes are used, not PGV's validator, so its other dependencies are left out.
    api(libs.pgv.java.stub) {
        exclude(group = "com.google.re2j")
        exclude(group = "commons-validator")
        exclude(group = "com.google.protobuf", module = "protobuf-java-util")
    }
    runtimeOnly(libs.grpc.netty.shaded)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.junit.platform.launcher)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                // @generated=omit: no javax.annotation.Generated, so no extra dependency.
                id("grpc") { option("@generated=omit") }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    options.release = 11
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Xlint:-options"))
}

tasks.compileTestJava {
    // Tests target 11 too, so CI can run them on every supported JDK.
    options.release = 11
}

// -PtestJavaVersion=11 runs the unit tests on that JDK (it must be installed; CI points
// Gradle at it with org.gradle.java.installations.fromEnv). Default: the build JDK.
val testJavaVersion = providers.gradleProperty("testJavaVersion")

// Writes the SDK version into the jar, for the user agent.
val versionResource =
    tasks.register("versionResource") {
        val out = layout.buildDirectory.dir("generated/resources/version")
        val v = project.version.toString()
        inputs.property("version", v)
        outputs.dir(out)
        doLast {
            val file = out.get().file("io/perms/permio-version.properties").asFile
            file.parentFile.mkdirs()
            file.writeText("version=$v\n")
        }
    }
sourceSets.main { resources.srcDir(versionResource) }

// The protobuf plugin copies the .proto sources into the jar at the classpath root (proto/...),
// where they could clash with other jars' files. The compiled descriptors are in the generated
// classes, so the .proto files are not needed at runtime: leave them out.
tasks.processResources {
    exclude("**/*.proto")
    includeEmptyDirs = false
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "io.perms.permio",
            "Implementation-Title" to "permio",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.javadoc {
    // The generated protobuf/gRPC sources are documented loosely; only fail on the hand-written API.
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        encoding = "UTF-8"
        links("https://docs.oracle.com/en/java/javase/11/docs/api/")
    }
    exclude("io/perms/proto/**")
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
    if (testJavaVersion.isPresent) {
        javaLauncher =
            javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(testJavaVersion.get())
            }
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Live tests against a real deployment (dev by default). They skip themselves
// unless PERMIO_API_KEY is set, and refuse to run against production.
val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs the live integration tests (needs PERMIO_API_KEY)."
        group = "verification"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("integration") }
        outputs.upToDateWhen { false }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.28.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Maven Central requires signatures. The release workflow passes the key as
    // ORG_GRADLE_PROJECT_signingInMemoryKey; without one (local publishToMavenLocal) nothing is signed.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "permio", project.version.toString())
    pom {
        name = "Permio Java SDK"
        description = "Java client for the perms.io permissions API."
        inceptionYear = "2026"
        url = "https://github.com/perms-io/permio-java"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        organization {
            name = "PrivateJAR"
            url = "https://github.com/perms-io"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/perms-io/permio-java/issues"
        }
        developers {
            developer {
                id = "PrivateJAR"
                name = "PrivateJAR"
                url = "https://github.com/perms-io"
            }
        }
        scm {
            url = "https://github.com/perms-io/permio-java"
            connection = "scm:git:https://github.com/perms-io/permio-java.git"
            developerConnection = "scm:git:ssh://git@github.com/perms-io/permio-java.git"
        }
    }
}
