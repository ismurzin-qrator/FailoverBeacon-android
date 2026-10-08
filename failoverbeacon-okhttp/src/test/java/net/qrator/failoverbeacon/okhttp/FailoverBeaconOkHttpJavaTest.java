package net.qrator.failoverbeacon.okhttp;

import static org.junit.Assert.assertNotNull;

import okhttp3.OkHttpClient;
import org.junit.Test;

public final class FailoverBeaconOkHttpJavaTest {
    @Test
    public void withFailoverBeaconIsCallableFromJava() {
        OkHttpClient client = FailoverBeaconOkHttp.withFailoverBeacon(new OkHttpClient());

        assertNotNull(client);
    }
}
