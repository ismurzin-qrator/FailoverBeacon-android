plugins {
    id("failoverbeacon.android-library")
    id("failoverbeacon.robolectric")
}

android {
    namespace = "net.qrator.failoverbeacon.telemetry"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
}
