plugins {
    id("failoverbeacon.public-api")
    id("failoverbeacon.okhttp-compatibility")
}

android {
    namespace = "net.qrator.failoverbeacon.okhttp"
}

dependencies {
    api(project(":failoverbeacon"))
    implementation(project(":failoverbeacon-core"))
    compileOnly(libs.squareup.okhttp)

    testImplementation(libs.squareup.mockwebserver)
}
