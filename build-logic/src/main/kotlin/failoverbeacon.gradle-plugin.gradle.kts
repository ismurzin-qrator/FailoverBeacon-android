import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-gradle-plugin`
    id("com.diffplug.spotless")
}

repositories {
    google()
    mavenCentral()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion).aosp()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
            .editorConfigOverride(mapOf("ktlint_code_style" to "android_studio"))
    }
}

tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}
