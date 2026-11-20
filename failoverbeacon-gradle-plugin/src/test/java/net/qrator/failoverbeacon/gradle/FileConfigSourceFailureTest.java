package net.qrator.failoverbeacon.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.gradle.api.InvalidUserDataException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class FileConfigSourceFailureTest {
    private static final String BEACONS = "'beacons': ['https://beacon.example']";

    @Parameters(name = "{1}")
    public static List<Object[]> cases() {
        return Arrays.asList(
                new Object[][] {
                    {"[]", "must be an object"},
                    {"{}", "beacons: beacons are required"},
                    {"{'beacons': []}", "beacons: beacons must not be empty"},
                    {"{'beacons': null}", "beacons: must be an array"},
                    {"{'beacons': [1]}", "beacons[0]: must be a string"},
                    {
                        "{'beacons': ['http://beacon.example']}",
                        "beacons[0]: Beacon URL must use https: http://beacon.example"
                    },
                    {
                        "{'beacons': ['https://beacon.example:0/']}",
                        "beacons[0]: Beacon URL port must be in 1..65535: https://beacon.example:0/"
                    },
                    {"{" + BEACONS + ", 'beacon': []}", "beacon: unknown key"},
                    {
                        "{" + BEACONS + ", 'blockedHosts': ['a.example:443']}",
                        "blockedHosts[0]: blockedHosts must contain host names without port,"
                                + " was \"a.example:443\""
                    },
                    {
                        "{" + BEACONS + ", 'successSampleRate': 1.5}",
                        "successSampleRate: successSampleRate must be in [0.0, 1.0], was 1.5"
                    },
                    {
                        "{" + BEACONS + ", 'failureSampleRate': '1'}",
                        "failureSampleRate: must be a number"
                    },
                    {hosts("{'port': 443}"), "failoverHosts[0].host: is required"},
                    {
                        hosts("{'host': '192.0.2.1'}"),
                        "failoverHosts[0].host: host must be a name, not an IP address,"
                                + " was \"192.0.2.1\""
                    },
                    {
                        hosts("{'host': 'a.example', 'port': 0}"),
                        "failoverHosts[0].port: port must be in 1..65535, was 0"
                    },
                    {
                        hosts("{'host': 'a.example', 'port': 443.0}"),
                        "failoverHosts[0].port: must be an integer"
                    },
                    {
                        hosts("{'host': 'a.example', 'additionalAddresses': ['192.0.2']}"),
                        "failoverHosts[0].additionalAddresses[0]: additionalAddresses must"
                                + " contain IPv4 addresses like 192.0.2.10, was \"192.0.2\""
                    },
                    {
                        hosts("{'host': 'a.example', 'tcpProbe': false, 'bodyProbe': {}}"),
                        "failoverHosts[0]: bodyProbe needs tcpProbe"
                    },
                    {
                        hosts("{'host': 'a.example', 'bodyProbe': {'path': 'health'}}"),
                        "failoverHosts[0].bodyProbe.path: path must start with / and have only"
                                + " visible ASCII, without a fragment, was \"health\""
                    },
                    {
                        hosts("{'host': 'a.example', 'bodyProbe': {'maxBytes': 100}}"),
                        "failoverHosts[0].bodyProbe.maxBytes: maxBytes must be in"
                                + " 1024..1048576, was 100"
                    },
                    {
                        hosts("{'host': 'a.example', 'bodyProbe': {'timeout': 1}}"),
                        "failoverHosts[0].bodyProbe.timeout: unknown key"
                    },
                    {
                        hosts("{'host': 'a.example', 'ports': [443]}"),
                        "failoverHosts[0].ports: unknown key"
                    },
                    {
                        hosts("{'host': 'a.example'}, {'host': 'A.EXAMPLE'}"),
                        "failoverHosts: failoverHosts contains \"a.example\" twice"
                    },
                    {
                        options("'uploadPeriodSeconds': 0"),
                        "options.uploadPeriodSeconds: uploadPeriod must be from 1 second"
                                + " to 8 hours, was 0 SECONDS"
                    },
                    {
                        options("'refreshIntervalSeconds': 30"),
                        "options.refreshIntervalSeconds: refreshInterval must be from"
                                + " 1 minute to 1 hour, was 30 SECONDS"
                    },
                    {
                        options("'diskQueueBytes': 0"),
                        "options.diskQueueBytes: diskQueueBytes must be > 0, was 0"
                    },
                    {
                        options("'memoryQueueSize': -1"),
                        "options.memoryQueueSize: memoryQueueSize must be > 0, was -1"
                    },
                    {
                        options("'fallbackWithoutEch': 'yes'"),
                        "options.fallbackWithoutEch: must be true or false"
                    },
                    {options("'uploadPeriod': 60"), "options.uploadPeriod: unknown key"},
                });
    }

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Parameter(0)
    public String json;

    @Parameter(1)
    public String message;

    @Test
    public void failsWithJsonPathAndRule() throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));

        InvalidUserDataException e =
                assertThrows(
                        InvalidUserDataException.class,
                        () ->
                                FileConfigSource.render(
                                        "failoverbeacon.json",
                                        "com.example.app",
                                        JsonValue.parse(file, "failoverbeacon.json")));

        assertEquals("failoverbeacon.json: " + message, e.getMessage());
    }

    private static String hosts(String hosts) {
        return "{" + BEACONS + ", 'failoverHosts': [" + hosts + "]}";
    }

    private static String options(String options) {
        return "{" + BEACONS + ", 'options': {" + options + "}}";
    }
}
