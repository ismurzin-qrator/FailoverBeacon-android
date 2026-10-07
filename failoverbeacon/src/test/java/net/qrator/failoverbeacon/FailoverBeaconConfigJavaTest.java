package net.qrator.failoverbeacon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Checks that the public API is usable from Java. */
public final class FailoverBeaconConfigJavaTest {
    @Test
    public void builderChainBuildsConfig() {
        FailoverBeaconOptions options = new FailoverBeaconOptions.Builder()
                .uploadPeriod(30, TimeUnit.SECONDS)
                .diskQueueSize(500)
                .memoryQueueSize(500)
                .build();

        FailoverBeaconConfig config = new FailoverBeaconConfig.Builder()
                .beacons(Collections.singletonList("https://beacon.example"))
                .blockedHosts(Collections.singletonList("analytics.example"))
                .failureSampleRate(1.0)
                .successSampleRate(0.05)
                .options(options)
                .build();

        assertNotNull(config);
    }

    @Test
    public void invalidArgumentThrowsIllegalArgumentException() {
        FailoverBeaconConfig.Builder builder = new FailoverBeaconConfig.Builder();

        assertThrows(IllegalArgumentException.class,
                () -> builder.beacons(Collections.singletonList("http://beacon.example")));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    public void consentIsRegularEnum() {
        assertEquals(Consent.PENDING, Consent.valueOf("PENDING"));
        assertEquals(3, Consent.getEntries().size());
    }

    @Test
    public void loggerIsLambda() {
        List<String> messages = new ArrayList<>();
        FailoverBeaconLogger logger = (level, message, throwable) -> messages.add(message);

        logger.log(5, "message", null);

        assertEquals(Collections.singletonList("message"), messages);
    }
}
