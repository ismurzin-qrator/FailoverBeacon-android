plugins {
    id("failoverbeacon.android-library")
}

android {
    namespace = "net.qrator.failoverbeacon.failover"
}

dependencies {
    implementation(project(":failoverbeacon-core"))
}
