pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.jetbrains.kotlin.multiplatform") {
                // The KBA-010 fork does not publish Gradle plugin marker modules.
                // Bind the requested id to its canonical implementation module.
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
            if (requested.id.id == "org.jetbrains.kotlin.plugin.compose") {
                useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/gradle-plugins/")
        }
    }
}

val raftPublicationMode = providers.gradleProperty("raftPublicationStagingDir")
    .orElse(providers.environmentVariable("RAFT_PUBLICATION_STAGING_DIR")).isPresent
val raftRequirePublicPredecessors = providers.environmentVariable("RAFT_REQUIRE_PUBLIC_PREDECESSORS")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
    .get()

dependencyResolutionManagement {
    repositories {
        if (raftRequirePublicPredecessors) {
            exclusiveContent {
                forRepository {
                    maven {
                        name = "raftArtifactsRequiredPredecessors"
                        url = uri("https://maven.artifacts.botiverse.dev")
                    }
                }
                filter {
                    includeModule("org.jetbrains.kotlin", "kotlin-stdlib")
                    includeModule("org.jetbrains.kotlin", "kotlin-stdlib-common")
                    includeGroupByRegex("com\\.tencent\\.kuikly-open\\.compose.*")
                }
            }
        } else {
            maven {
                name = "raftArtifactsCandidatePredecessors"
                url = uri("https://maven.artifacts.botiverse.dev")
                content {
                    includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
                    includeGroupByRegex("com\\.tencent\\.kuikly-open\\.compose.*")
                }
            }
        }
        if (!raftPublicationMode) {
            mavenLocal()
        }
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

val buildFileName = "build.2.0.ohos.gradle.kts"
rootProject.buildFileName = buildFileName
val kuiklyOhosOnly = providers.gradleProperty("kuiklyOhosOnly")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
    .get()


include(":core-annotations")
project(":core-annotations").buildFileName = buildFileName

include(":core-ksp")
project(":core-ksp").buildFileName = buildFileName

include(":core")
project(":core").buildFileName = buildFileName

include(":compose")
project(":compose").buildFileName = buildFileName

if (!kuiklyOhosOnly) {
    include(":core-render-android")
    project(":core-render-android").buildFileName = buildFileName

    include(":core-wx")
    project(":core-wx").buildFileName = buildFileName

    include(":demo")
    project(":demo").buildFileName = buildFileName
}

// include(":androidApp")
