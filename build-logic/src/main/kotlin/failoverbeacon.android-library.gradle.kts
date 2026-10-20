import org.gradle.api.tasks.testing.logging.TestExceptionFormat
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

tasks.withType<JavaCompile>().configureEach {
    // Java 8 bytecode is pinned on purpose; "options" only reports that 8 is obsolete on JDK 21.
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
}

tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
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

    testImplementation(libs.findLibrary("junit").get())
    testImplementation(libs.findLibrary("kotlin-test-junit").get())
}
