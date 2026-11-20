package net.qrator.failoverbeacon.gradle;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FileConfigSourceTest {
    private static final String JSON = "{ \"beacons\": [\"https://beacon.example\"] }";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void fileWithByteOrderMarkRendersLikeOneWithout() throws IOException {
        assertEquals(render(JSON), render("﻿" + JSON));
    }

    private String render(String json) throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return FileConfigSource.render(
                "failoverbeacon.json",
                "com.example.app",
                JsonValue.parse(file, "failoverbeacon.json"));
    }
}
