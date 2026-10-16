plugins {
    id("failoverbeacon.public-api")
    id("failoverbeacon.robolectric")
}

android {
    namespace = "net.qrator.failoverbeacon"

    // Robolectric picks the SDK from the manifest; without it, tests would run below minSdk.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":failoverbeacon-core"))
    implementation(project(":failoverbeacon-telemetry"))
}
