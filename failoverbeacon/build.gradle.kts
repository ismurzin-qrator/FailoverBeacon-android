plugins {
    id("failoverbeacon.public-api")
    id("failoverbeacon.robolectric")
    id("failoverbeacon.publish")
}

android {
    namespace = "net.qrator.failoverbeacon"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
    implementation(project(":failoverbeacon-failover"))
    implementation(project(":failoverbeacon-telemetry"))
}
