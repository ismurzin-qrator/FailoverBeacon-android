package net.qrator.failoverbeacon.gradle;

import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.tasks.TaskProvider;

public final class FailoverBeaconPlugin implements Plugin<Project> {
    private static final String ANDROID_APPLICATION_PLUGIN = "com.android.application";
    private static final String CONFIG_FILE = "src/main/failoverbeacon.json";

    @Override
    public void apply(Project project) {
        project.getPluginManager()
                .withPlugin(ANDROID_APPLICATION_PLUGIN, applied -> registerVariants(project));
    }

    private static void registerVariants(Project project) {
        ApplicationAndroidComponentsExtension components =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        Action<ApplicationVariant> register = variant -> register(project, variant);
        components.onVariants(components.selector().all(), register);
    }

    private static void register(Project project, ApplicationVariant variant) {
        RegularFile configFile = project.getLayout().getProjectDirectory().file(CONFIG_FILE);
        Action<GenerateFailoverBeaconConfig> configure =
                generate -> {
                    generate.getConfigFile().set(configFile);
                    generate.getNamespace().set(variant.getNamespace());
                };
        TaskProvider<GenerateFailoverBeaconConfig> task =
                project.getTasks()
                        .register(taskName(variant), GenerateFailoverBeaconConfig.class, configure);
        variant.getSources()
                .getJava()
                .addGeneratedSourceDirectory(
                        task, GenerateFailoverBeaconConfig::getOutputDirectory);
    }

    private static String taskName(ApplicationVariant variant) {
        String name = variant.getName();
        return "generate"
                + Character.toUpperCase(name.charAt(0))
                + name.substring(1)
                + "FailoverBeaconConfig";
    }
}
