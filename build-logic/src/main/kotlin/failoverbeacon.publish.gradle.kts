import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish.base")
}

val repository = "github.com/ismurzin-qrator/FailoverBeacon-android"

mavenPublishing {
    coordinates(project.group.toString(), project.name, project.version.toString())
    publishToMavenCentral(automaticRelease = false)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set(project.name)
        description.set("Failover and network failure telemetry for OkHttp on Android.")
        url.set("https://$repository")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                name.set("Ivan Murzin")
            }
        }
        scm {
            connection.set("scm:git:https://$repository.git")
            developerConnection.set("scm:git:ssh://git@$repository.git")
            url.set("https://$repository")
        }
    }
}

pluginManager.withPlugin("com.android.library") {
    mavenPublishing {
        configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
    }
    dependencies {
        constraints {
            rootProject.subprojects.filter { it != project }.forEach { library ->
                library.pluginManager.withPlugin("com.android.library") { add("api", library) }
            }
        }
    }
}

pluginManager.withPlugin("java-gradle-plugin") {
    mavenPublishing {
        configure(GradlePlugin(JavadocJar.Empty(), SourcesJar.Sources()))
    }
}
