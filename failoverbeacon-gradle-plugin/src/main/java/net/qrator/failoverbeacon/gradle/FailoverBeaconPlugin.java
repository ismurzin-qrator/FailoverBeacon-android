package net.qrator.failoverbeacon.gradle;

import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import kotlin.Pair;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

public final class FailoverBeaconPlugin implements Plugin<Project> {
    private static final String ANDROID_APPLICATION_PLUGIN = "com.android.application";
    private static final String CONFIG_FILE = "failoverbeacon.json";
    private static final String SDK_MODULE = "sdk-module.txt";
    private static final String SDK_VERSION_REASON =
            "The FailoverBeacon plugin generates code for this SDK version";

    @Override
    public void apply(Project project) {
        project.getPluginManager()
                .withPlugin(
                        ANDROID_APPLICATION_PLUGIN,
                        applied -> {
                            requireSdkVersion(project);
                            registerVariants(project);
                        });
        project.afterEvaluate(FailoverBeaconPlugin::requireAndroidApplication);
    }

    private static void requireAndroidApplication(Project project) {
        if (!project.getPluginManager().hasPlugin(ANDROID_APPLICATION_PLUGIN)) {
            throw new GradleException(
                    "The FailoverBeacon plugin needs "
                            + ANDROID_APPLICATION_PLUGIN
                            + " in "
                            + project.getPath());
        }
    }

    private static void requireSdkVersion(Project project) {
        project.getDependencies()
                .getConstraints()
                .add(
                        "implementation",
                        sdkModule(),
                        constraint -> constraint.because(SDK_VERSION_REASON));
    }

    private static String sdkModule() {
        try (InputStream in = FailoverBeaconPlugin.class.getResourceAsStream(SDK_MODULE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void registerVariants(Project project) {
        ApplicationAndroidComponentsExtension components =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        Action<ApplicationVariant> register = variant -> register(project, variant);
        components.onVariants(components.selector().all(), register);
    }

    private static void register(Project project, ApplicationVariant variant) {
        Action<GenerateFailoverBeaconConfig> configure =
                generate -> {
                    for (String directory : sourceDirectories(variant)) {
                        generate.getConfigFiles().from("src/" + directory + "/" + CONFIG_FILE);
                    }
                    generate.getNamespace().set(variant.getNamespace());
                    generate.getVariantName().set(variant.getName());
                };
        TaskProvider<GenerateFailoverBeaconConfig> task =
                project.getTasks()
                        .register(taskName(variant), GenerateFailoverBeaconConfig.class, configure);
        variant.getSources()
                .getJava()
                .addGeneratedSourceDirectory(
                        task, GenerateFailoverBeaconConfig::getOutputDirectory);
    }

    private static Set<String> sourceDirectories(ApplicationVariant variant) {
        Set<String> directories = new LinkedHashSet<>();
        directories.add(variant.getName());
        addIfSet(directories, variant.getFlavorName());
        for (Pair<String, String> flavor : variant.getProductFlavors()) {
            directories.add(flavor.getSecond());
        }
        addIfSet(directories, variant.getBuildType());
        directories.add("main");
        return directories;
    }

    private static void addIfSet(Set<String> directories, String directory) {
        if (directory != null && !directory.isEmpty()) {
            directories.add(directory);
        }
    }

    private static String taskName(ApplicationVariant variant) {
        String name = variant.getName();
        return "generate"
                + Character.toUpperCase(name.charAt(0))
                + name.substring(1)
                + "FailoverBeaconConfig";
    }
}
