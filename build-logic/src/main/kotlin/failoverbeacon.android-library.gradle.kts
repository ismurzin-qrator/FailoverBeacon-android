import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
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

dependencies {
    implementation(libs.findLibrary("kotlin-stdlib").get())
}
