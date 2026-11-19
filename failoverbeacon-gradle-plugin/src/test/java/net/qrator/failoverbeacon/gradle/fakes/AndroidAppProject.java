package net.qrator.failoverbeacon.gradle.fakes;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.testkit.runner.GradleRunner;

public final class AndroidAppProject {
    public static final String CONFIG_FILE = "failoverbeacon.json";

    private final Path root;

    private AndroidAppProject(Path root) {
        this.root = root;
    }

    public static AndroidAppProject create(Path root, String... androidBlock) throws IOException {
        AndroidAppProject project = new AndroidAppProject(root);
        String sdkAar = System.getProperty("failoverbeacon.sdkAar").replace('\\', '/');
        String androidSdk = System.getProperty("failoverbeacon.androidSdk").replace('\\', '/');

        List<String> build = new ArrayList<>();
        build.add("plugins {");
        build.add("    id 'com.android.application'");
        build.add("    id 'net.qrator.failoverbeacon'");
        build.add("}");
        build.add("repositories { google(); mavenCentral() }");
        build.add("android {");
        build.add("    namespace = 'com.example.app'");
        build.add("    compileSdk = " + System.getProperty("failoverbeacon.compileSdk"));
        build.add("    defaultConfig { minSdk = 24 }");
        build.addAll(Arrays.asList(androidBlock));
        build.add("}");
        build.add("dependencies { implementation files('" + sdkAar + "') }");

        project.write("settings.gradle", "rootProject.name = 'fixture'");
        project.write("local.properties", "sdk.dir=" + androidSdk);
        project.write("build.gradle", build.toArray(new String[0]));
        project.write("src/main/AndroidManifest.xml", "<manifest />");
        project.write(
                "src/main/java/com/example/app/App.java",
                "package com.example.app;",
                "",
                "final class App {",
                "    static Object config() {",
                "        return FailoverBeaconFileConfig.builder().build();",
                "    }",
                "}");
        return project;
    }

    public void write(String name, String... lines) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        String text = String.join("\n", lines);
        Files.write(
                file, (text.endsWith("\n") ? text : text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public void addToBuild(String... lines) throws IOException {
        Files.write(
                root.resolve("build.gradle"),
                Arrays.asList(lines),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    public void publishPom(String module, String version) throws IOException {
        String[] coordinates = module.split(":");
        write(
                "repo/"
                        + coordinates[0].replace('.', '/')
                        + "/"
                        + coordinates[1]
                        + "/"
                        + version
                        + "/"
                        + coordinates[1]
                        + "-"
                        + version
                        + ".pom",
                "<project>",
                "  <modelVersion>4.0.0</modelVersion>",
                "  <groupId>" + coordinates[0] + "</groupId>",
                "  <artifactId>" + coordinates[1] + "</artifactId>",
                "  <version>" + version + "</version>",
                "  <packaging>pom</packaging>",
                "</project>");
    }

    public void writeConfig(String sourceSet, String json) throws IOException {
        write("src/" + sourceSet + "/" + CONFIG_FILE, json);
    }

    public GradleRunner runner(String... tasks) {
        GradleRunner runner = runnerWithoutConfigurationCache(tasks);
        List<String> arguments = new ArrayList<>(runner.getArguments());
        arguments.add("--configuration-cache");
        return runner.withArguments(arguments);
    }

    public GradleRunner runnerWithoutConfigurationCache(String... tasks) {
        List<String> arguments = new ArrayList<>(Arrays.asList(tasks));
        arguments.add("--stacktrace");
        GradleRunner runner =
                GradleRunner.create()
                        .withProjectDir(root.toFile())
                        .withTestKitDir(new File(System.getProperty("failoverbeacon.testKitDir")))
                        .withPluginClasspath()
                        .withArguments(arguments);
        String gradleVersion = System.getProperty("failoverbeacon.gradleVersion");
        return gradleVersion == null ? runner : runner.withGradleVersion(gradleVersion);
    }

    public String generatedSource(String task) throws IOException {
        try (Stream<Path> files = Files.walk(root.resolve("build"))) {
            List<Path> found =
                    files.filter(path -> path.toString().contains(task))
                            .filter(path -> path.endsWith("FailoverBeaconFileConfig.java"))
                            .collect(Collectors.toList());
            assertEquals(found.toString(), 1, found.size());
            return new String(Files.readAllBytes(found.get(0)), StandardCharsets.UTF_8);
        }
    }

    public static String resource(String name) throws IOException {
        try (InputStream in = AndroidAppProject.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
