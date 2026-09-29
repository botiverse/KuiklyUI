import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import groovy.util.Node

fun Node.raftDirectChildren(name: String): List<Node> =
    children().filterIsInstance<Node>().filter {
        it.name().toString().substringAfterLast('}') == name
    }

fun Node.raftUniqueText(name: String, coordinate: String): String {
    val values = raftDirectChildren(name)
    require(values.size == 1) {
        "POM dependency $coordinate must have exactly one $name"
    }
    return values.single().text().toString().also { value ->
        require(value.isNotBlank() && value.trim() == value) {
            "POM dependency $coordinate has malformed $name"
        }
    }
}

fun raftPublicationPrimaryKindIndex(project: Project): Map<String, String> {
    val entries = mutableListOf<Pair<String, String>>()
    project.rootProject.allprojects.forEach { candidateProject ->
        candidateProject.extensions.findByType(PublishingExtension::class.java)
            ?.publications
            ?.withType(MavenPublication::class.java)
            ?.forEach { publication ->
                val coordinate = "${publication.groupId}:${publication.artifactId}:${publication.version}"
                val primaries = publication.artifacts.filter { artifact ->
                    artifact.classifier.isNullOrEmpty()
                }
                require(primaries.size == 1) {
                    "publication $coordinate must expose exactly one unclassified primary artifact"
                }
                entries += coordinate to primaries.single().extension
            }
    }
    val duplicate = entries.groupBy({ it.first }, { it.second })
        .entries.firstOrNull { (_, kinds) -> kinds.size != 1 }
    require(duplicate == null) {
        "publication primary-kind index must contain each coordinate exactly once: " +
            "${duplicate?.key}: ${duplicate?.value}"
    }
    return entries.toMap()
}

fun Node.raftBindAarDependencyTypes(primaryKinds: Map<String, String>) {
    val dependenciesContainers = raftDirectChildren("dependencies")
    require(dependenciesContainers.size <= 1) { "POM contains duplicate dependencies containers" }
    dependenciesContainers.singleOrNull()?.raftDirectChildren("dependency")?.forEach { dependency ->
        val group = dependency.raftUniqueText("groupId", "unknown")
        val artifact = dependency.raftUniqueText("artifactId", group)
        val version = dependency.raftUniqueText("version", "$group:$artifact")
        val coordinate = "$group:$artifact:$version"
        val primaryKind = primaryKinds[coordinate] ?: return@forEach
        val types = dependency.raftDirectChildren("type")
        require(types.size <= 1) { "POM dependency $coordinate has duplicate type fields" }
        if (primaryKind == "aar") {
            if (types.isEmpty()) {
                dependency.appendNode("type", "aar")
            } else {
                require(types.single().text().toString() == "aar") {
                    "POM dependency $coordinate targets AAR but declares a different type"
                }
            }
        } else {
            require(types.none { it.text().toString() == "aar" }) {
                "POM dependency $coordinate does not target AAR but declares type aar"
            }
        }
    }
}

/*
 * Publication boundary for the immutable Raft release-set lane.
 *
 * Gradle is deliberately only a producer: it writes into an empty, caller-
 * supplied file repository.  No repository credential and no Raft URL are
 * accepted here.  The release assembler validates every staged byte before a
 * separate ordinary Maven writer uses the repository-scoped token. That
 * writer reuses exact bytes, rejects conflicts, and writes the manifest last.
 */
val raftPublicationStagingDir = providers.gradleProperty("raftPublicationStagingDir")
    .orElse(providers.environmentVariable("RAFT_PUBLICATION_STAGING_DIR"))

val publicationSourceSha = providers.gradleProperty("publicationSourceSha")
    .orElse(providers.environmentVariable("PUBLICATION_SOURCE_SHA"))
    .map { value ->
        require(value.matches(Regex("[0-9a-f]{40}"))) {
            "publicationSourceSha must be the exact 40-character lowercase commit SHA"
        }
        value
    }

extensions.configure<PublishingExtension> {
    if (raftPublicationStagingDir.isPresent) {
        repositories {
            maven {
                name = "raftPublicationStaging"
                url = uri(file(raftPublicationStagingDir.get()).toURI())
            }
        }
        publications.withType<MavenPublication>().configureEach {
            pom {
                properties.put("dev.raft.sourceSha", publicationSourceSha)
                scm {
                    connection.set("scm:git:https://github.com/botiverse/KuiklyUI.git")
                    developerConnection.set("scm:git:ssh://git@github.com/botiverse/KuiklyUI.git")
                    url.set("https://github.com/botiverse/KuiklyUI")
                    tag.set(publicationSourceSha)
                }
            }
        }
    }
}

// Kotlin MPP installs publication-specific dependency rewrites during project
// evaluation. Register this final producer normalization only after every
// project has finished configuring so it sees the actual emitted POM graph.
gradle.projectsEvaluated {
    if (raftPublicationStagingDir.isPresent) {
        project.extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom.withXml {
                    asNode().raftBindAarDependencyTypes(
                        raftPublicationPrimaryKindIndex(project)
                    )
                }
            }
        }
    }
}
