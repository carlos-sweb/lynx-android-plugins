import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

apply(plugin = "maven-publish")
apply(plugin = "signing")

val mavenArtifactId = extra["mavenArtifactId"] as? String
    ?: error("Set extra[\"mavenArtifactId\"] before applying publish-library.gradle.kts")
val mavenDescription = extra["mavenDescription"] as? String
    ?: error("Set extra[\"mavenDescription\"] before applying publish-library.gradle.kts")
val signingKey = providers.environmentVariable("GPG_PRIVATE_KEY")
val signingPassword = providers.environmentVariable("GPG_PASSPHRASE")
val useLocalGpg = providers.environmentVariable("GPG_USE_COMMAND")
    .map(String::toBoolean)
    .orElse(false)

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(rootProject.file("CENTRAL_JAVADOC.md")) {
        rename { "README.md" }
    }
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "centralStaging"
                url = rootProject.layout.buildDirectory.dir("central-staging/repository").get().asFile.toURI()
            }
        }
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifact(javadocJar)
                groupId = project.group.toString()
                artifactId = mavenArtifactId
                version = project.version.toString()

                pom {
                    name.set(mavenArtifactId)
                    description.set(mavenDescription)
                    url.set(providers.gradleProperty("POM_URL").get())
                    licenses {
                        license {
                            name.set("The MIT License")
                            url.set("https://opensource.org/license/mit")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("carlos-sweb")
                            name.set("Carlos")
                            url.set("https://github.com/carlos-sweb")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/carlos-sweb/lynx-android-plugins.git")
                        developerConnection.set("scm:git:ssh://git@github.com/carlos-sweb/lynx-android-plugins.git")
                        url.set("https://github.com/carlos-sweb/lynx-android-plugins")
                    }
                }
            }
        }
    }

    if (signingKey.isPresent) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    } else if (useLocalGpg.get()) {
        extensions.configure<SigningExtension> {
            useGpgCmd()
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
}
