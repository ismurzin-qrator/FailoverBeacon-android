plugins {
    id("failoverbeacon.android-library")
    id("failoverbeacon.robolectric")
}

android {
    namespace = "net.qrator.failoverbeacon.failover"
}

dependencies {
    implementation(project(":failoverbeacon-core"))

    testImplementation(libs.squareup.okhttp.tls)
}
