import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("failoverbeacon.android-library")
    id("failoverbeacon.robolectric")
}

val telemetrySchemaVersion = providers.gradleProperty("telemetrySchemaVersion").get()

android {
    namespace = "net.qrator.failoverbeacon.telemetry"

    buildFeatures.buildConfig = true
    defaultConfig.buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    defaultConfig.buildConfigField("String", "SCHEMA_VERSION", "\"$telemetrySchemaVersion\"")
}

dependencies {
    implementation(project(":failoverbeacon-core"))

    testImplementation(libs.squareup.mockwebserver)
}

abstract class CopyTelemetrySchema @Inject constructor(
    private val exec: ExecOperations,
    private val files: FileSystemOperations
) : DefaultTask() {
    @get:Input
    abstract val repository: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun copy() {
        val clone = temporaryDir.resolve("clone")
        clone.deleteRecursively()
        exec.exec {
            commandLine(
                "git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1",
                "--branch", "v${version.get()}", repository.get(), clone.path
            )
        }
        clone.resolve("SHA256SUMS").readLines().filter { it.isNotBlank() }.forEach { line ->
            val (hash, path) = line.split("  ", limit = 2)
            val bytes = MessageDigest.getInstance("SHA-256").digest(clone.resolve(path).readBytes())
            val actual = bytes.joinToString("") { "%02x".format(it) }
            check(actual == hash) { "$path does not match SHA256SUMS of v${version.get()}" }
        }
        files.sync {
            from(clone) { include("VERSION", "SHA256SUMS", "schemas/**", "samples/**") }
            into(destination)
        }
    }
}

tasks.register<CopyTelemetrySchema>("copyTelemetrySchema") {
    description = "Replaces the schema copy in the tests with the tag in telemetrySchemaVersion."
    repository = providers.gradleProperty("telemetrySchemaRepository")
    version = telemetrySchemaVersion
    destination = layout.projectDirectory.dir("src/test/resources/telemetry-schema")
    doNotTrackState("The tag lives in another repository")
}
