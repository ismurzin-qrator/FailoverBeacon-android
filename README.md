# FailoverBeacon for Android

FailoverBeacon keeps OkHttp calls to your important hosts working when DNS fails, a server stops answering or a network path breaks. You list the hosts and wrap your client, and the SDK picks working addresses in the background. It also reports failed requests, including the ones that never reached your server, to an HTTPS endpoint you run, called the beacon.

- When DNS is down, calls to your hosts still go through on saved DNS answers and the addresses you ship with the app.
- Requests rarely wait for a dead or slow server to time out. The SDK checks your servers in the background and gives OkHttp a working address first.
- The SDK ranks addresses separately for each network, because an address blocked on one carrier may still work on Wi-Fi.
- On OkHttp 5.4 and newer the SDK stops OkHttp from retrying a POST to your failover hosts on its own. You mark the requests that are safe to repeat.
- OkHttp, Retrofit, interceptors and certificate pinning work without code changes.
- Each failed request is reported to your beacon with the step where it failed, the network and the carrier.

Requirements: `minSdk` 24, OkHttp 3.14.9 or newer (tested with 3.14.9, 4.12.0, 5.2.3, 5.3.2, 5.4.0 and 5.5.0), Kotlin and Java.

## Install

The SDK uses your app's OkHttp. Add it next to OkHttp:

```kotlin
dependencies {
    implementation("net.qrator.failoverbeacon:failoverbeacon-okhttp:1.0.0")
}
```

The artifacts are not published to a Maven repository yet.

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
            .failoverHosts(
                listOf(
                    FailoverHost(
                        "pay.example.com",
                        additionalAddresses = listOf("192.0.2.10", "192.0.2.11")
                    )
                )
            )
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
                        new FailoverHost(
                                "pay.example.com",
                                443,
                                Arrays.asList("192.0.2.10", "192.0.2.11"))))
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

Use the wrapped client everywhere, Retrofit included. Set your interceptors and `dns()` first and call `withFailoverBeacon()` last. The order of `withFailoverBeacon()` and `initialize` does not matter.

## Decide before release

1. Consent. `initialize` uses `Consent.GRANTED` by default. If your app asks the user, pass `Consent.PENDING` and call `FailoverBeacon.setConsent` once the user decides. `PENDING` stores events without uploading them, and `NOT_GRANTED` deletes them. The SDK does not store consent, so pass it on every launch.
2. Hosts. List the hosts your important user flows depend on in `failoverHosts`, using the exact names from your URLs. Add their IPv4 addresses as `additionalAddresses`. On the first launch they are the only addresses the SDK can use during a DNS outage, and the SDK needs at least two IPv4 addresses per host to choose between them.
3. POST retries. On OkHttp 5.4 and newer the SDK stops OkHttp from sending a non-idempotent request to a failover host twice. Mark a request with `idempotent()` only if a repeat does no harm:

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

   An unmarked POST can then fail with an `IOException` where OkHttp would have retried it, for example after the server closed a pooled connection. With Retrofit, mark requests in an interceptor added before `withFailoverBeacon()`, for example by an annotation read from the request's `retrofit2.Invocation` tag.

## Data collected

For each request the SDK reports the host, port, method, result, timings, connection attempts, TLS version and issuer, network state and carrier. It never records the path, query, headers or body. Events carry no account, device or advertising ID. Uploads go to your beacons over HTTPS. The failover state stays on the device, and `clearAllData()` deletes everything the SDK stored.

For the Google Play Data safety form:

| Form question | Answer for the SDK |
| --- | --- |
| Data types collected | App info and performance, Diagnostics |
| Approximate location | Not collected. Events carry the carrier's MCC, which names the country of the SIM |
| Device or other IDs | Not collected |
| Shared with third parties | The SDK sends data only to the beacons you configure |
| Encrypted in transit | Yes |

## Limits

- Failover covers only the hosts in `failoverHosts` and only wrapped clients.
- Calls through an HTTP or SOCKS proxy, a Wi-Fi proxy included, do not use SDK addresses.
- A server that went silent on a live pooled HTTP/2 connection fails calls while OkHttp keeps the connection. `client.connectionPool.evictAll()` drops it.
- Any HTTP status counts as a response, including 429, 5xx and block pages.
- IPv6 addresses get no probes, and a host with a single IPv4 address has nothing to rank.
- Before OkHttp 5.4, OkHttp may still retry a POST after a connection failure.
- The SDK runs only in the app's main process.
- In a day the TCP probes use about 50 KB of traffic and the uploads about 170 KB. A body probe, which you enable per host, costs about 20 KB each time. The SDK adds about 64 KB to a release APK.

## Support and security

Ask questions and report bugs in GitHub Issues. To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Run `./gradlew check` before you open a pull request.

## License

MIT. See [LICENSE](LICENSE).
