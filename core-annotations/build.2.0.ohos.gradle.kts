plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    id("com.android.library")
    id("maven-publish")
    signing
}

apply(from = rootProject.file("gradle/raft-artifacts-publishing.gradle.kts"))
val raftPublicationMode = providers.gradleProperty("raftPublicationStagingDir")
    .orElse(providers.environmentVariable("RAFT_PUBLICATION_STAGING_DIR")).isPresent

group = MavenConfig.GROUP
version = Version.getCoreVersion()
val kuiklyOhosOnly = providers.gradleProperty("kuiklyOhosOnly")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
    .get()

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

    afterEvaluate {
        publications.withType<MavenPublication>().configureEach {
            pom.configureMavenCentralMetadata()
            signPublicationIfKeyPresent(project)
        }
        // for mavenCentral verify
        publications.named<MavenPublication>("jvm") {
            artifact(emptyJavadocJar)
        }
    }
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions {
                moduleName = "${project.group}.${project.name}"
            }
        }
    }

    ohosArm64()
    if (!kuiklyOhosOnly) {
        android {
            compilations.all {
                kotlinOptions {
                    moduleName = "${project.group}.${project.name}"
                }
            }
        }

        iosArm64()
        iosX64()
        iosSimulatorArm64()

        js(IR) {
            browser()
        }

        cocoapods {
            summary = "Some description for the Shared Module"
            homepage = "Link to the Shared Module homepage"
            ios.deploymentTarget = "14.1"
//        framework {
//            baseName = "core-annotations"
//        }
        }
    }

    sourceSets {
        val commonMain by getting
    }
}

android {
    compileSdk = 32
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 21
        targetSdk = 32
    }
}

val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}
