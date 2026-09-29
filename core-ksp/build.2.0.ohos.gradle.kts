plugins {
    kotlin("jvm")
    id("maven-publish")
    signing
}

apply(from = rootProject.file("gradle/raft-artifacts-publishing.gradle.kts"))
val raftPublicationMode = providers.gradleProperty("raftPublicationStagingDir")
    .orElse(providers.environmentVariable("RAFT_PUBLICATION_STAGING_DIR")).isPresent

group = MavenConfig.GROUP
version = Version.getCoreVersion()

publishing {
    repositories {
        if (!raftPublicationMode) {
            val username = MavenConfig.getUsername(project)
            val password = MavenConfig.getPassword(project)
            if (username.isNotEmpty() && password.isNotEmpty()) {
                maven {
                    credentials {
                        setUsername(username)
                        setPassword(password)
                    }
                    url = uri(MavenConfig.getRepoUrl(version as String))
                }
            } else {
                mavenLocal()
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }

    afterEvaluate {
        publications.withType<MavenPublication>().configureEach {
            pom.configureMavenCentralMetadata()
            signPublicationIfKeyPresent(project)
            artifact(emptyJavadocJar)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        moduleName = "${project.group}.${project.name}"
    }
}

dependencies {
    implementation(Dependencies.kotlinpoet)
    implementation("com.google.devtools.ksp:symbol-processing-api:2.0.21-1.0.27")
    implementation(project(":core-annotations"))
}

val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}
