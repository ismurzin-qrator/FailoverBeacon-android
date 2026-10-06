plugins {
    id("failoverbeacon.android-library")
}

android {
    namespace = "net.qrator.failoverbeacon"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
    implementation(project(":failoverbeacon-telemetry"))
}
