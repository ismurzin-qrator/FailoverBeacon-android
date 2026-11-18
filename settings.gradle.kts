pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "failoverbeacon-android"

include(":failoverbeacon")
include(":failoverbeacon-core")
include(":failoverbeacon-failover")
include(":failoverbeacon-gradle-plugin")
include(":failoverbeacon-okhttp")
include(":failoverbeacon-telemetry")
