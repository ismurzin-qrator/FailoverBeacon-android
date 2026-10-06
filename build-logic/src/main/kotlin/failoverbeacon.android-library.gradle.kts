import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    id("com.diffplug.spotless")
}

repositories {
    google()
    mavenCentral()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        aarMetadata {
            minCompileSdk = minSdk
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        warningsAsErrors = true
        // Gradle, AGP and stdlib versions are pinned on purpose for Kotlin compatibility.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
        jvmTarget = JvmTarget.JVM_1_8
        allWarningsAsErrors = true
    }
}

spotless {
    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    val ktlintStyle = mapOf("ktlint_code_style" to "android_studio")

    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintStyle)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintStyle)
    }
}

dependencies {
    implementation(libs.findLibrary("kotlin-stdlib").get())
}
