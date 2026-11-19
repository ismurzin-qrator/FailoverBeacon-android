import java.util.Properties
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

plugins {
    id("failoverbeacon.gradle-plugin")
}

sourceSets.main {
    java.srcDir(rootDir.resolve("failoverbeacon-core/src/main/java"))
}

val testAndroidGradlePlugin: Configuration by configurations.creating

dependencies {
    compileOnly(libs.android.gradle.api.minimum)
    compileOnly(libs.kotlin.stdlib)

    testImplementation(libs.junit)
    testAndroidGradlePlugin(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("failoverBeacon") {
            id = "net.qrator.failoverbeacon"
            implementationClass = "net.qrator.failoverbeacon.gradle.FailoverBeaconPlugin"
        }
    }
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testAndroidGradlePlugin)
}

val sdkModule = "$group:failoverbeacon:$version"

tasks.processResources {
    val module = sdkModule
    inputs.property("sdkModule", module)
    filesMatching("**/sdk-module.txt") { expand("sdkModule" to module) }
}

val sdkAar = rootDir.resolve("failoverbeacon/build/outputs/aar/failoverbeacon-release.aar")
val androidSdkDir: Provider<String> = providers.environmentVariable("ANDROID_HOME").orElse(
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir") }
)
val testKitDir = gradle.gradleUserHomeDir.resolve("failoverbeacon-testkit")

tasks.withType<Test>().configureEach {
    dependsOn(":failoverbeacon:bundleReleaseAar")
    inputs.file(sdkAar)
    systemProperty("failoverbeacon.sdkAar", sdkAar.absolutePath)
    systemProperty("failoverbeacon.androidSdk", androidSdkDir.get())
    systemProperty("failoverbeacon.testKitDir", testKitDir.absolutePath)
    systemProperty("failoverbeacon.sdkModule", sdkModule)
}

tasks.test {
    systemProperty("failoverbeacon.compileSdk", "36")
}

class PluginTestTarget(val agp: String, val gradle: String, val jdk: Int, val compileSdk: Int)

val pluginTestTargets = listOf(
    PluginTestTarget(libs.versions.agp.api.minimum.get(), "7.4.2", 17, 34),
    PluginTestTarget("8.0.2", "8.0.2", 17, 34),
    PluginTestTarget("8.13.2", "8.14.3", 17, 36),
    PluginTestTarget("9.5.0", "9.8.0", 21, 36)
)

val pluginTests = pluginTestTargets.map { target ->
    val name = "Agp_" + target.agp.replace('.', '_')
    val androidGradlePlugin = configurations.create("testAndroidGradlePlugin$name")
    dependencies.add(androidGradlePlugin.name, "com.android.tools.build:gradle:${target.agp}")
    val metadata = tasks.register<PluginUnderTestMetadata>("pluginUnderTestMetadata$name") {
        pluginClasspath.from(sourceSets.main.get().runtimeClasspath, androidGradlePlugin)
        outputDirectory.set(layout.buildDirectory.dir("pluginUnderTestMetadata$name"))
    }
    tasks.register<Test>("test$name") {
        group = "verification"
        description = "Runs the plugin tests with AGP ${target.agp} on Gradle ${target.gradle}."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = files(metadata.flatMap { it.outputDirectory }) +
            sourceSets.test.get().runtimeClasspath -
            files(tasks.pluginUnderTestMetadata.flatMap { it.outputDirectory })
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(target.jdk))
            }
        )
        systemProperty("failoverbeacon.gradleVersion", target.gradle)
        systemProperty("failoverbeacon.compileSdk", target.compileSdk.toString())
    }
}

tasks.check {
    dependsOn(pluginTests)
}
