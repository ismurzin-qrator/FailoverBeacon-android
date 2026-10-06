plugins {
    id("failoverbeacon.public-api")
}

android {
    namespace = "net.qrator.failoverbeacon.okhttp"
}

dependencies {
    api(project(":failoverbeacon"))
    implementation(project(":failoverbeacon-core"))
    compileOnly(libs.squareup.okhttp)
}
