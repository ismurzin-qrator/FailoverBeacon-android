plugins {
    id("failoverbeacon.public-api")
}

android {
    namespace = "net.qrator.failoverbeacon"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
    implementation(project(":failoverbeacon-telemetry"))
}
