package net.qrator.failoverbeacon.okhttp;

import static org.junit.Assert.assertNotNull;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

public final class FailoverBeaconOkHttpJavaTest {
    @Test
    public void withFailoverBeaconIsCallableFromJava() {
        OkHttpClient client = FailoverBeaconOkHttp.withFailoverBeacon(new OkHttpClient());

        assertNotNull(client);
    }

    @Test
    public void idempotentIsCallableFromJava() {
        Request request = FailoverBeaconOkHttp.idempotent(new Request.Builder().url("https://api.test/"))
                .build();

        assertNotNull(request);
    }
}
