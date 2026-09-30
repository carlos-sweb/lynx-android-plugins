import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

apply(plugin = "maven-publish")

val mavenArtifactId = extra["mavenArtifactId"] as? String
    ?: error("Set extra[\"mavenArtifactId\"] before applying publish-library.gradle.kts")
val mavenDescription = extra["mavenDescription"] as? String
    ?: error("Set extra[\"mavenDescription\"] before applying publish-library.gradle.kts")

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = mavenArtifactId
                version = project.version.toString()

                pom {
                    name.set(mavenArtifactId)
                    description.set(mavenDescription)
                    url.set(providers.gradleProperty("POM_URL").get())
                    scm {
                        connection.set("scm:git:git://github.com/carlos-sweb/lynx-android-plugins.git")
                        developerConnection.set("scm:git:ssh://git@github.com/carlos-sweb/lynx-android-plugins.git")
                        url.set("https://github.com/carlos-sweb/lynx-android-plugins")
                    }
                }
            }
        }
    }
}
