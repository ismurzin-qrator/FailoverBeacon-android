plugins {
    id("failoverbeacon.public-api")
    id("failoverbeacon.robolectric")
}

android {
    namespace = "net.qrator.failoverbeacon"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
    implementation(project(":failoverbeacon-telemetry"))
}
