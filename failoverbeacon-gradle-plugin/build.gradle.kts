import java.util.Properties

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
    testImplementation(gradleTestKit())
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

val sdkAar = rootDir.resolve("failoverbeacon/build/outputs/aar/failoverbeacon-release.aar")
val androidSdkDir: Provider<String> = providers.environmentVariable("ANDROID_HOME").orElse(
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir") }
)

tasks.test {
    dependsOn(":failoverbeacon:bundleReleaseAar")
    inputs.file(sdkAar)
    systemProperty("failoverbeacon.sdkAar", sdkAar.absolutePath)
    systemProperty("failoverbeacon.androidSdk", androidSdkDir.get())
}
