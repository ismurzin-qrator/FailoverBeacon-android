package net.qrator.failoverbeacon.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FailoverBeaconPluginTest {
    private static final String CONFIG_FILE = "src/main/failoverbeacon.json";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private Path project;

    @Before
    public void createProject() throws IOException {
        project = folder.getRoot().toPath();
        String sdkAar = System.getProperty("failoverbeacon.sdkAar").replace('\\', '/');
        String androidSdk = System.getProperty("failoverbeacon.androidSdk").replace('\\', '/');

        write("settings.gradle", "rootProject.name = 'fixture'");
        write("local.properties", "sdk.dir=" + androidSdk);
        write(
                "build.gradle",
                "plugins {",
                "    id 'com.android.application'",
                "    id 'net.qrator.failoverbeacon'",
                "}",
                "repositories { google(); mavenCentral() }",
                "android {",
                "    namespace = 'com.example.app'",
                "    compileSdk = 36",
                "    defaultConfig { minSdk = 24 }",
                "}",
                "dependencies { implementation files('" + sdkAar + "') }");
        write("src/main/AndroidManifest.xml", "<manifest />");
        write(
                "src/main/java/com/example/app/App.java",
                "package com.example.app;",
                "",
                "final class App {",
                "    static Object config() {",
                "        return FailoverBeaconFileConfig.builder().build();",
                "    }",
                "}");
    }

    @Test
    public void generatedBuilderCompilesInTheAppAndCarriesTheFileValues() throws IOException {
        write(CONFIG_FILE, resource("failoverbeacon.json"));

        BuildResult result = runner("compileDebugJavaWithJavac").build();

        assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":generateDebugFailoverBeaconConfig").getOutcome());
        assertEquals(resource("FailoverBeaconFileConfig.java.expected"), generatedSource());
    }

    @Test
    public void valueOfWrongTypeFailsTheBuildWithItsPath() throws IOException {
        write(
                CONFIG_FILE,
                "{ \"failoverHosts\": [ { \"host\": \"pay.example\", \"port\": \"443\" } ] }");

        BuildResult result = runner("generateDebugFailoverBeaconConfig").buildAndFail();

        String expected = CONFIG_FILE + ": failoverHosts[0].port must be an integer";
        assertTrue(result.getOutput(), result.getOutput().contains(expected));
    }

    private GradleRunner runner(String task) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(task, "--configuration-cache", "--stacktrace");
    }

    private String generatedSource() throws IOException {
        try (Stream<Path> files = Files.walk(project.resolve("build/generated"))) {
            List<Path> found =
                    files.filter(path -> path.endsWith("FailoverBeaconFileConfig.java"))
                            .collect(Collectors.toList());
            assertEquals(found.toString(), 1, found.size());
            return new String(Files.readAllBytes(found.get(0)), StandardCharsets.UTF_8);
        }
    }

    private void write(String name, String... lines) throws IOException {
        Path file = project.resolve(name);
        Files.createDirectories(file.getParent());
        String text = String.join("\n", lines);
        Files.write(
                file, (text.endsWith("\n") ? text : text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = FailoverBeaconPluginTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
