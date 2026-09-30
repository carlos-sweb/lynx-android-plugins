import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

val centralModules = listOf(
    ":android-core",
    ":android-battery",
    ":android-camera",
    ":android-device",
    ":android-geolocation",
    ":android-network",
    ":android-vibration",
    ":android-all",
)
val centralRepositoryDirectory = layout.buildDirectory.dir("central-staging/repository")
val centralBundleDirectory = layout.buildDirectory.dir("central-bundle")
val centralGroupPath = providers.gradleProperty("GROUP").get().replace('.', '/')
val centralVersion = providers.gradleProperty("VERSION_NAME").get()

fun checksum(file: Path, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun regularFiles(directory: Path): List<Path> = Files.walk(directory).use { stream ->
    stream.filter { it.isRegularFile() }.toList()
}

val cleanCentralStaging = tasks.register("cleanCentralStaging") {
    group = "publishing"
    description = "Removes the temporary Maven Central staging repository."

    doLast {
        delete(centralRepositoryDirectory)
    }
}

val prepareCentralBundle = tasks.register("prepareCentralBundle") {
    group = "publishing"
    description = "Builds a signed Maven Central Portal bundle without uploading or publishing it."

    doFirst {
        check(
            providers.environmentVariable("GPG_PRIVATE_KEY").isPresent ||
                providers.environmentVariable("GPG_USE_COMMAND").getOrElse("false").toBoolean(),
        ) {
            "Set GPG_PRIVATE_KEY or GPG_USE_COMMAND=true to sign a Maven Central bundle."
        }
    }

    doLast {
        val repositoryDirectory = centralRepositoryDirectory.get().asFile.toPath()
        val groupDirectory = repositoryDirectory.resolve(centralGroupPath)
        check(Files.isDirectory(groupDirectory)) {
            "No staged artifacts found at $groupDirectory."
        }

        regularFiles(groupDirectory)
            .filter { it.fileName.toString().startsWith("maven-metadata.xml") }
            .forEach(Files::delete)

        val checksumExtensions = setOf("md5", "sha1", "sha256", "sha512")
        val publishedFiles = regularFiles(groupDirectory).filter { file ->
            file.fileName.toString().substringAfterLast('.', "") !in checksumExtensions &&
                !file.fileName.toString().endsWith(".asc")
        }
        check(publishedFiles.isNotEmpty()) { "No publishable artifacts were staged." }

        publishedFiles.forEach { file ->
            check(Files.isRegularFile(file.resolveSibling("${file.fileName}.asc"))) {
                "Missing PGP signature for ${file.fileName}. Ensure GPG_PRIVATE_KEY is valid."
            }
            mapOf(
                "MD5" to "md5",
                "SHA-1" to "sha1",
                "SHA-256" to "sha256",
                "SHA-512" to "sha512",
            ).forEach { (algorithm, extension) ->
                Files.writeString(file.resolveSibling("${file.fileName}.$extension"), checksum(file, algorithm))
            }
        }

        val bundleDirectory = centralBundleDirectory.get().asFile.toPath()
        Files.createDirectories(bundleDirectory)
        val bundle = bundleDirectory.resolve("lynx-android-plugins-$centralVersion.zip")
        Files.deleteIfExists(bundle)
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            regularFiles(groupDirectory).sorted().forEach { file ->
                val entryName = repositoryDirectory.relativize(file).toString()
                    .replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(entryName))
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }

        logger.lifecycle("Maven Central bundle created: $bundle")
    }
}

gradle.projectsEvaluated {
    val publicationTasks = centralModules.map { module ->
        project(module).tasks.named("publishReleasePublicationToCentralStagingRepository")
    }
    publicationTasks.forEach { task -> task.configure { dependsOn(cleanCentralStaging) } }
    prepareCentralBundle.configure { dependsOn(publicationTasks) }
}
