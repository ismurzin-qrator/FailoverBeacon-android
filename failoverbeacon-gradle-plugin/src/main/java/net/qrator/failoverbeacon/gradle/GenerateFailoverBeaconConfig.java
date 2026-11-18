package net.qrator.failoverbeacon.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class GenerateFailoverBeaconConfig extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getConfigFile();

    @Input
    public abstract Property<String> getNamespace();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract ProjectLayout getLayout();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void generate() throws IOException {
        File configFile = getConfigFile().get().getAsFile();
        Path projectDirectory = getLayout().getProjectDirectory().getAsFile().toPath();
        String file = projectDirectory.relativize(configFile.toPath()).toString();
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
}
