plugins {
    id("failoverbeacon.android-library")
}

android {
    namespace = "net.qrator.failoverbeacon.telemetry"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
}
