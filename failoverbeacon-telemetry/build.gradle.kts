plugins {
    id("failoverbeacon.android-library")
    id("failoverbeacon.robolectric")
}

android {
    namespace = "net.qrator.failoverbeacon.telemetry"

    buildFeatures.buildConfig = true
    defaultConfig.buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
}

dependencies {
    implementation(project(":failoverbeacon-core"))

    testImplementation(libs.squareup.mockwebserver)
}
