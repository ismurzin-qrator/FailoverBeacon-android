plugins {
    id("failoverbeacon.public-api")
}

android {
    namespace = "net.qrator.failoverbeacon"

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric on API 36 reflects into JDK internals.
        // https://robolectric.org/getting-started/
        unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    implementation(project(":failoverbeacon-core"))
    implementation(project(":failoverbeacon-telemetry"))

    testImplementation(libs.robolectric)
}
