import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension
import ru.vyarus.gradle.plugin.animalsniffer.signature.BuildSignatureTask

plugins {
    id("ru.vyarus.animalsniffer")
    id("failoverbeacon.okhttp-test-matrix")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val okhttpMinimum by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    okhttpMinimum(libs.findLibrary("squareup-okhttp-minimum").get()) { isTransitive = false }
}

val okhttpMinimumSignature by tasks.registering(BuildSignatureTask::class) {
    group = "animalsniffer"
    description = "Builds the API signature of the minimum supported OkHttp version."
    files(okhttpMinimum)
}

configure<AnimalSnifferExtension> {
    signatures = files(okhttpMinimumSignature.map { it.outputFiles })
    excludeJars = listOf("okhttp-*")
    ignore = listOf("java.*", "javax.*", "android.*")
    annotation = "net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck"
}

// Disables default method compatibility bridges to avoid referencing APIs present only in newer versions.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
}
