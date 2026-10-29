plugins {
    id("failoverbeacon.public-api")
    id("failoverbeacon.okhttp-compatibility")
    id("failoverbeacon.robolectric")
}

android {
    namespace = "net.qrator.failoverbeacon.okhttp"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":failoverbeacon"))
    implementation(project(":failoverbeacon-core"))
    compileOnly(libs.squareup.okhttp)

    testImplementation(project(":failoverbeacon-telemetry"))
    testImplementation(libs.squareup.mockwebserver)
    testImplementation(libs.squareup.okhttp.tls)
}
