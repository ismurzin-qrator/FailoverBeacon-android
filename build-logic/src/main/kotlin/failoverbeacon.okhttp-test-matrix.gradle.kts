import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.LibraryAndroidComponentsExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val okhttpLatest = libs.findVersion("okhttp-latest").get().requiredVersion
val okhttpTestVersions =
    listOf("okhttp-minimum", "okhttp-4", "okhttp-5-2", "okhttp-5-3", "okhttp-5-5")
        .map { libs.findVersion(it).get().requiredVersion }
        .filter { it != okhttpLatest }
        .distinct()

extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants(selector().withBuildType("debug")) { variant ->
        val unitTest = checkNotNull((variant as HasUnitTest).unitTest) {
            "The OkHttp test matrix needs unit tests of the ${variant.name} variant"
        }
        val unitTestTask = "test${variant.name.replaceFirstChar { it.uppercase() }}UnitTest"
        val okhttpTests = okhttpTestVersions.map { version ->
            registerOkHttpTest(version, unitTestTask, unitTest.runtimeConfiguration)
        }
        tasks.named("check") { dependsOn(okhttpTests) }
    }
}

fun registerOkHttpTest(
    version: String,
    baseTaskName: String,
    baseRuntime: Configuration
): TaskProvider<Test> {
    val name = "OkHttp_" + version.replace('.', '_')
    val runtime = configurations.register("unitTestRuntimeClasspath$name") {
        isCanBeConsumed = false
        extendsFrom(baseRuntime)
        baseRuntime.attributes.keySet().forEach { attributes.copy(it, baseRuntime.attributes) }
        resolutionStrategy.eachDependency {
            if (requested.group == "com.squareup.okhttp3") useVersion(version)
        }
    }
    return tasks.register<Test>("test$name") {
        group = "verification"
        description = "Runs the unit tests against OkHttp $version."
        val baseTask = tasks.getByName<Test>(baseTaskName)
        testClassesDirs = baseTask.testClassesDirs
        classpath = externalLibraries(runtime.get()) +
            (baseTask.classpath - externalLibraries(baseRuntime))
        javaLauncher.set(baseTask.javaLauncher)
        jvmArgs(baseTask.jvmArgs)
        systemProperties(baseTask.systemProperties)
        systemProperty("failoverbeacon.okhttpVersion", version)
        useJUnit()
    }
}

fun externalLibraries(configuration: Configuration): FileCollection = files(
    listOf("android-classes-jar", "android-java-res").map { type ->
        configuration.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
            attributes { attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, type) }
        }.files
    }
)

fun <T : Any> AttributeContainer.copy(key: Attribute<T>, from: AttributeContainer) {
    from.getAttribute(key)?.let { attribute(key, it) }
}
