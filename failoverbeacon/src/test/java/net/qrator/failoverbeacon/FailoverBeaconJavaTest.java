package net.qrator.failoverbeacon;

import android.app.Application;
import android.util.Log;
import java.util.Collections;
import net.qrator.failoverbeacon.core.SdkRegistry;
import net.qrator.failoverbeacon.internal.LogcatLogger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public final class FailoverBeaconJavaTest {
    private final Application app = RuntimeEnvironment.getApplication();
    private final FailoverBeaconConfig config = new FailoverBeaconConfig.Builder()
            .beacons(Collections.singletonList("https://beacon.example"))
            .build();

    @After
    public void tearDown() {
        SdkRegistry.INSTANCE.clear();
        SdkRegistry.INSTANCE.getLogger().setMinLevel(Log.WARN);
        SdkRegistry.INSTANCE.getLogger().setOutput(LogcatLogger.INSTANCE);
    }

    @Test
    public void everyMethodIsCallableFromJava() {
        FailoverBeacon.setLogLevel(Log.DEBUG);
        FailoverBeacon.setLogger((level, message, throwable) -> { });
        FailoverBeacon.setLogger(null);
        FailoverBeacon.initialize(app, config);
        FailoverBeacon.initialize(app, config, Consent.PENDING);
        FailoverBeacon.setConsent(Consent.GRANTED);
        FailoverBeacon.clearAllData();
        FailoverBeacon.stop();
    }
}
