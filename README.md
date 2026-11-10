# FailoverBeacon for Android

FailoverBeacon keeps OkHttp requests to your critical hosts working when DNS or some server addresses fail. It resolves and probes the addresses of these hosts in the background and gives OkHttp the working ones first. It also reports how your app's requests end, network errors included, to your beacon server.

**Requirements:** `minSdk` 24, OkHttp 3.14.9 or newer (tested with 3.14.9, 4.12.0, 5.2.3, 5.3.2, 5.4.0, 5.5.0), Kotlin or Java.

## Install

The SDK uses your app's OkHttp. Add the SDK next to it:

```kotlin
dependencies {
    implementation("net.qrator.failoverbeacon:failoverbeacon-okhttp:0.1.0")
}
```

The artifacts are not published to a Maven repository yet. Publication is pending.

## Set up

Initialize the SDK in `Application.onCreate` and wrap your `OkHttpClient`.

Kotlin:

```kotlin
import net.qrator.failoverbeacon.okhttp.withFailoverBeacon

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = FailoverBeaconConfig.Builder()
            .beacons(listOf("https://beacon.example.com/reports"))
            .failoverHosts(listOf(FailoverHost("pay.example.com")))
            .build()
        FailoverBeacon.initialize(this, config)
    }
}

val client: OkHttpClient = OkHttpClient.Builder()
    .build()
    .withFailoverBeacon()
```

Java:

```java
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FailoverBeaconConfig config = new FailoverBeaconConfig.Builder()
                .beacons(Collections.singletonList(
                        "https://beacon.example.com/reports"))
                .failoverHosts(Collections.singletonList(
                        new FailoverHost("pay.example.com")))
                .build();
        FailoverBeacon.initialize(this, config);
    }
}
```

```java
import net.qrator.failoverbeacon.okhttp.FailoverBeaconOkHttp;

OkHttpClient client = FailoverBeaconOkHttp.withFailoverBeacon(
        new OkHttpClient.Builder().build());
```

Use the wrapped client everywhere, Retrofit included. Calls to `withFailoverBeacon()` and `initialize` work in any order.

## Decide before release

1. **Consent.** `initialize` uses `Consent.GRANTED` by default. If your app asks the user, pass `Consent.PENDING` and call `FailoverBeacon.setConsent` when the user decides. `PENDING` stores events without uploading them. `NOT_GRANTED` deletes them. The SDK does not store consent, so pass it on every launch.
2. **Which hosts.** List in `failoverHosts` the hosts whose calls must survive failures, by the exact name in your URLs. Add `additionalAddresses` with known IPv4 addresses for the case when DNS fails. Probes and ranking need at least two IPv4 addresses per host.
3. **Safe POSTs.** On OkHttp 5.4 and newer the SDK stops OkHttp from sending a non-idempotent request to a failover host twice. Mark a request with `idempotent()` only if a repeat is harmless:

   ```kotlin
   import net.qrator.failoverbeacon.okhttp.idempotent
   import okhttp3.MediaType.Companion.toMediaType
   import okhttp3.RequestBody.Companion.toRequestBody

   val body = "{}".toRequestBody("application/json".toMediaType())
   val request = Request.Builder()
       .url("https://pay.example.com/v1/status")
       .post(body)
       .idempotent()
       .build()
   ```

   ```java
   RequestBody body = RequestBody.create(
           "{}", MediaType.get("application/json"));
   Request.Builder builder = new Request.Builder()
           .url("https://pay.example.com/v1/status")
           .post(body);
   Request request = FailoverBeaconOkHttp.idempotent(builder).build();
   ```

   An unmarked POST then fails with an `IOException` when the server closed a pooled connection.

## Limits

- Failover covers only the hosts in `failoverHosts` and only clients wrapped with `withFailoverBeacon()`.
- A silent IP on a live pooled HTTP/2 connection keeps failing until you call `connectionPool.evictAll()`.
- Any HTTP status counts as a working address, including 429, 5xx and block pages.
- IPv6 addresses get no probes. A host with one IPv4 address gets no ranking.
- On OkHttp before 5.4, OkHttp may still send a POST twice after a connection failure.
- The SDK runs only in the app's main process.
- Background traffic with default options: about 51 KB a day of TCP probes in a lab scenario. Each body probe costs about 20 KB, and you enable it per host.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Run `./gradlew check` before you open a pull request.

## License

MIT. See [LICENSE](LICENSE).
