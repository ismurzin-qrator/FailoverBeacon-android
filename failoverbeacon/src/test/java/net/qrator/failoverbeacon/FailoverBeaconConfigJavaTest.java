package net.qrator.failoverbeacon;

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
                                Collections.<String>emptyList(), false),
                        new FailoverHost("pay.example", 443,
                                Collections.<String>emptyList(), true,
                                new FailoverBodyProbe("/probe.bin", 64 * 1024)),
                        new FailoverHost("root.example", 443,
                                Collections.<String>emptyList(), true,
                                new FailoverBodyProbe())))
                .options(options)
                .build();
    }
}
