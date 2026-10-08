package net.qrator.failoverbeacon;

import static org.junit.Assert.assertThrows;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class FailoverBeaconConfigJavaTest {
    @Test
    public void everyBuilderMethodIsCallableFromJava() {
        FailoverBeaconOptions options = new FailoverBeaconOptions.Builder()
                .uploadPeriod(30, TimeUnit.SECONDS)
                .diskQueueSize(500)
                .memoryQueueSize(500)
                .build();

        new FailoverBeaconConfig.Builder()
                .beacons(Collections.singletonList("https://beacon.example"))
                .blockedHosts(Collections.singletonList("analytics.example"))
                .failureSampleRate(1.0)
                .successSampleRate(0.05)
                .options(options)
                .build();
    }

    @Test
    public void invalidArgumentThrowsIllegalArgumentException() {
        FailoverBeaconConfig.Builder builder = new FailoverBeaconConfig.Builder();

        assertThrows(IllegalArgumentException.class,
                () -> builder.beacons(Collections.singletonList("http://beacon.example")));
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
