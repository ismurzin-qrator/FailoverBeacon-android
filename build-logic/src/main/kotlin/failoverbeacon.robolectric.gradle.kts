plugins {
    id("com.android.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    testOptions {
        // Robolectric on API 37 reflects into JDK internals.
        // https://robolectric.org/getting-started/
        unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    "testImplementation"(libs.findLibrary("robolectric").get())
}
