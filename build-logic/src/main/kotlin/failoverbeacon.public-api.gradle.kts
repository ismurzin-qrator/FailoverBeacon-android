import kotlinx.validation.KotlinApiBuildTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("failoverbeacon.android-library")
    id("kotlin-android")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

kotlin {
    explicitApi()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// AGP 9 built-in Kotlin leaves the validator without the compiled classes and without
// kotlin-metadata-jvm in its worker (KT-83410). Remove with that fix.
val kotlinMetadata: Configuration = configurations.create("apiValidationKotlinMetadata") {
    isCanBeConsumed = false
}
dependencies.add(
    kotlinMetadata.name,
    "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.findVersion("kotlin").get().requiredVersion}"
)

tasks.withType<KotlinApiBuildTask>().configureEach {
    val compileTask = tasks.named<KotlinCompile>("compileReleaseKotlin")
    inputClassesDirs.from(compileTask.flatMap { it.destinationDirectory })
    runtimeClasspath.from(kotlinMetadata)
}
