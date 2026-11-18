package net.qrator.failoverbeacon.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Generating the source is cheaper than a cache lookup")
public abstract class GenerateFailoverBeaconConfig extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getConfigFiles();

    @Input
    public abstract Property<String> getNamespace();

    @Internal
    public abstract Property<String> getVariantName();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void generate() throws IOException {
        File configFile = firstExisting();
        String file = relative(configFile);
        String namespace = getNamespace().get();
        String source = FileConfigSource.render(file, namespace, JsonValue.parse(configFile, file));

        File outputDirectory = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(outputDirectory));
        Path packageDirectory = outputDirectory.toPath().resolve(namespace.replace('.', '/'));
        Files.createDirectories(packageDirectory);
        Files.write(
                packageDirectory.resolve(FileConfigSource.CLASS_NAME + ".java"),
                source.getBytes(StandardCharsets.UTF_8));
    }

    private File firstExisting() {
        List<String> searched = new ArrayList<>();
        for (File candidate : getConfigFiles().getFiles()) {
            if (candidate.isFile()) {
                return candidate;
            }
            searched.add(relative(candidate));
        }
        throw new InvalidUserDataException(
                "No config file for variant "
                        + getVariantName().get()
                        + ". Searched: "
                        + String.join(", ", searched));
    }

    private String relative(File file) {
        Path projectDirectory = getLayout().getProjectDirectory().getAsFile().toPath();
        return projectDirectory.relativize(file.toPath()).toString().replace('\\', '/');
    }
}
