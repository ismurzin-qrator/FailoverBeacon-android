package net.qrator.failoverbeacon;

import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class FailoverBeaconConfigJavaTest {
    @Test
    public void everyBuilderMethodIsCallableFromJava() {
        FailoverBeaconOptions options = new FailoverBeaconOptions.Builder()
                .uploadPeriod(30, TimeUnit.SECONDS)
                .diskQueueBytes(1024 * 1024)
                .memoryQueueSize(500)
                .preresolveOnNetworkChange(false)
                .probeOnNetworkChange(false)
                .refreshInterval(5, TimeUnit.MINUTES)
                .build();

        new FailoverBeaconConfig.Builder()
                .beacons(Collections.singletonList("https://beacon.example"))
                .blockedHosts(Collections.singletonList("analytics.example"))
                .failureSampleRate(1.0)
                .successSampleRate(0.05)
                .failoverHosts(Arrays.asList(
                        new FailoverHost("api.example"),
                        new FailoverHost("api2.example", 8443),
                        new FailoverHost("api3.example", 443,
                                Collections.singletonList("192.0.2.10")),
                        new FailoverHost("partner.example", 443,
                                Collections.<String>emptyList(), false)))
                .options(options)
                .build();
    }

    @Test
    public void invalidArgumentThrowsIllegalArgumentException() {
        FailoverBeaconConfig.Builder builder = new FailoverBeaconConfig.Builder();

        assertThrows(IllegalArgumentException.class,
                () -> builder.beacons(Collections.singletonList("http://beacon.example")));
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> new FailoverHost("api.example", 0));
    }
}
