package net.qrator.failoverbeacon.gradle;

import static net.qrator.failoverbeacon.gradle.fakes.AndroidAppProject.resource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import net.qrator.failoverbeacon.gradle.fakes.AndroidAppProject;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FailoverBeaconPluginTest {
    private static final String GENERATE = "generateDebugFailoverBeaconConfig";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private AndroidAppProject project;

    @Before
    public void createProject() throws IOException {
        project = AndroidAppProject.create(folder.getRoot().toPath());
    }

    @Test
    public void generatedBuilderCompilesInTheAppAndCarriesTheFileValues() throws IOException {
        project.writeConfig("main", resource("failoverbeacon.json"));

        BuildResult result = project.runner("compileDebugJavaWithJavac").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":" + GENERATE).getOutcome());
        assertEquals(
                resource("FailoverBeaconFileConfig.java.expected"),
                project.generatedSource(GENERATE));
    }

    @Test
    public void invalidValueFailsTheBuildWithItsPathAndRule() throws IOException {
        project.writeConfig(
                "main",
                "{ \"beacons\": [\"https://beacon.example\"],"
                        + " \"failoverHosts\": [ { \"host\": \"pay.example\", \"port\": 0 } ] }");

        BuildResult result = project.runner(GENERATE).buildAndFail();

        String expected =
                "src/main/failoverbeacon.json: failoverHosts[0].port: port must be in 1..65535,"
                        + " was 0";
        assertTrue(result.getOutput(), result.getOutput().contains(expected));
    }

    @Test
    public void olderSdkInTheAppResolvesToThePluginVersion() throws IOException {
        String sdkModule = System.getProperty("failoverbeacon.sdkModule");
        String module = sdkModule.substring(0, sdkModule.lastIndexOf(':'));
        String version = sdkModule.substring(sdkModule.lastIndexOf(':') + 1);
        project.publishPom(module, "0.1.0");
        project.publishPom(module, version);
        project.addToBuild(
                "repositories { maven { url = 'repo' } }",
                "dependencies { implementation '" + module + ":0.1.0' }");

        // Gradle 7.4 cannot store dependencyInsight in the configuration cache.
        BuildResult result =
                project.runnerWithoutConfigurationCache(
                                "dependencyInsight",
                                "--configuration",
                                "releaseRuntimeClasspath",
                                "--dependency",
                                module)
                        .build();

        assertTrue(
                result.getOutput(), result.getOutput().contains(module + ":0.1.0 -> " + version));
        assertTrue(
                result.getOutput(),
                result.getOutput()
                        .contains("The FailoverBeacon plugin generates code for this SDK version"));
    }

    @Test
    public void newerSdkInTheAppKeepsItsVersion() throws IOException {
        String sdkModule = System.getProperty("failoverbeacon.sdkModule");
        String module = sdkModule.substring(0, sdkModule.lastIndexOf(':'));
        String version = sdkModule.substring(sdkModule.lastIndexOf(':') + 1);
        project.publishPom(module, version);
        project.publishPom(module, "99.0.0");
        project.addToBuild(
                "repositories { maven { url = 'repo' } }",
                "dependencies { implementation '" + module + ":99.0.0' }");

        // Gradle 7.4 cannot store dependencyInsight in the configuration cache.
        BuildResult result =
                project.runnerWithoutConfigurationCache(
                                "dependencyInsight",
                                "--configuration",
                                "releaseRuntimeClasspath",
                                "--dependency",
                                module)
                        .build();

        assertTrue(result.getOutput(), result.getOutput().contains(module + ":99.0.0\n"));
        assertFalse(result.getOutput(), result.getOutput().contains(module + ":99.0.0 -> "));
    }

    @Test
    public void pluginAppliedBeforeAndroidApplicationStillGenerates() throws IOException {
        project.swapPluginOrder();
        project.writeConfig("main", resource("failoverbeacon.json"));

        BuildResult result = project.runner(GENERATE).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":" + GENERATE).getOutcome());
    }

    @Test
    public void pluginWithoutAndroidApplicationFailsTheBuild() throws IOException {
        project.write(
                "build.gradle",
                "plugins {",
                "    id 'java-library'",
                "    id 'net.qrator.failoverbeacon'",
                "}");

        BuildResult result = project.runner("help").buildAndFail();

        String expected = "The FailoverBeacon plugin needs com.android.application in :";
        assertTrue(result.getOutput(), result.getOutput().contains(expected));
    }
}
