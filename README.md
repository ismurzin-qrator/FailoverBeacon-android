# FailoverBeacon for Android

Keep payments and logins working when DNS fails or a server goes dark. FailoverBeacon gives OkHttp a working address for your critical hosts and reports every failed request to your server, even the ones that never reached it.

- Calls go through when DNS is down.
- Dead and slow servers move to the back of the line, so users rarely wait for a timeout.
- Each network gets its own ranking: an address blocked on one carrier can still serve Wi-Fi.
- OkHttp never resends a POST to your critical hosts on its own (5.4+).
- No changes to your requests. OkHttp, Retrofit, interceptors and certificate pinning work as before.

| | Without the SDK | With the SDK |
| --- | --- | --- |
| DNS does not answer | Every call fails | Calls go through |
| Server does not accept connections, OkHttp 4 | ~12 s per call | ~3 s |
| TLS hangs, OkHttp 5 | ~11 s | ~1 s |
| Everything works | Normal speed | Same speed |

Measured on Android emulators against deliberately faulty servers.

Requires `minSdk` 24 and OkHttp 3.14.9 or newer (tested up to 5.5.0). Works from Kotlin and Java.

## Install

```kotlin
dependencies {
    implementation("net.qrator.failoverbeacon:failoverbeacon-okhttp:1.0.0")
}
```

The SDK uses your app's OkHttp. The artifacts are not published to a Maven repository yet.

## Set up

List your critical hosts, initialize in `Application.onCreate` and wrap your client. The beacon is the HTTPS endpoint that receives failure reports.

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

Use the wrapped client everywhere, Retrofit included, and call `withFailoverBeacon()` after your own interceptors and `dns()`.

### Configuration file

The configuration can live in `app/src/main/failoverbeacon.json` instead, with the builder's option names as keys. A file in `src/<build type>/`, `src/<flavor>/` or `src/<variant>/` replaces it for that variant. The Gradle plugin checks the file at build time and generates `FailoverBeaconFileConfig` in your app's namespace. It needs AGP 7.3 and Gradle 7.4 or newer.

```kotlin
plugins {
    id("com.android.application")
    id("net.qrator.failoverbeacon") version "1.0.0"
}
```

A file with every key:

```json
{
  "beacons": ["https://beacon.example.com/reports"],
  "blockedHosts": ["analytics.example.com"],
  "failureSampleRate": 1.0,
  "successSampleRate": 0.1,
  "failoverHosts": [
    {
      "host": "pay.example.com",
      "port": 443,
      "additionalAddresses": ["192.0.2.10", "192.0.2.11"],
      "tcpProbe": true,
      "bodyProbe": { "path": "/health", "maxBytes": 16384 }
    }
  ],
  "options": {
    "uploadPeriodSeconds": 60,
    "diskQueueBytes": 5242880,
    "memoryQueueSize": 1000,
    "refreshIntervalSeconds": 600,
    "fallbackWithoutEch": true
  }
}
```

Only `beacons` is required, and `host` in each failover host. A key you leave out keeps its default:

| Key | Value | Default | Meaning and bounds |
| --- | --- | --- | --- |
| `beacons` | Array of strings | Required | Beacon URLs, at least one. Each must use `https://`, have a host, a port from 1 to 65535 if set, and no user info |
| `blockedHosts` | Array of strings | `[]` | Hosts that are never reported, without port. Exact match in any case. IPv4 literals allowed |
| `failureSampleRate` | Number | `1.0` | Share of failed requests reported, 0.0 to 1.0 |
| `successSampleRate` | Number | `0.1` | Share of successful requests reported, 0.0 to 1.0 |
| `failoverHosts` | Array of objects | `[]` | Hosts to protect. A name may appear once |
| `failoverHosts[].host` | String | Required | A host name in Unicode or Punycode, without scheme, port, path or IP address |
| `failoverHosts[].port` | Integer | `443` | 1 to 65535 |
| `failoverHosts[].additionalAddresses` | Array of strings | `[]` | IPv4 literals such as `192.0.2.10` |
| `failoverHosts[].tcpProbe` | `true` or `false` | `true` | Background TCP connections that rank the addresses by connect time |
| `failoverHosts[].bodyProbe` | Object | None | A background HTTPS request that checks the leading address delivers data. Needs `tcpProbe` to be `true` |
| `failoverHosts[].bodyProbe.path` | String | `/` | A static file of at least `maxBytes` bytes that supports `Range`. Starts with `/`, visible ASCII, no `#`. A query is allowed |
| `failoverHosts[].bodyProbe.maxBytes` | Integer | `16384` | 1024 to 1048576 |
| `options` | Object | Defaults below | Fine tuning |
| `options.uploadPeriodSeconds` | Integer | `60` | How often stored events are uploaded, 1 to 28800 (8 hours) |
| `options.diskQueueBytes` | Integer | `5242880` (5 MB) | Disk space for events, above 0 |
| `options.memoryQueueSize` | Integer | `1000` | Events waiting in memory for the disk, above 0 |
| `options.refreshIntervalSeconds` | Integer | `600` | How often failover hosts are resolved and probed again, 60 to 3600 |
| `options.fallbackWithoutEch` | `true` or `false` | `true` | Android 17 and newer: whether to use SDK addresses without an ECH key while your DNS has not answered yet |

An unknown key, a wrong type or `null` fails the build with the file, the JSON path and the rule. Initialize with the generated builder, and set `dnsResolver` or any other option in code. A value set in code replaces the file's:

```kotlin
FailoverBeacon.initialize(this, FailoverBeaconFileConfig.builder().build())
```

## Before you ship

- Hosts. List every host your key user flows depend on, with the exact names from your URLs. Ship their IPv4 addresses in `additionalAddresses`: they are the only way through a DNS outage on first launch.
- Consent. Telemetry starts as `GRANTED`. If you ask users first, pass `Consent.PENDING` and call `setConsent` with their answer on every launch.
- POST retries. On OkHttp 5.4+ an unmarked POST to a failover host is never retried, so it can fail where OkHttp used to resend it. Mark requests that are safe to repeat:

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

## Privacy

Each report holds the host, method, result, timings, connection attempts, TLS details, network state and carrier. No paths, queries, headers or bodies, and no account, device or advertising IDs. Reports go only to your beacons, over HTTPS. `clearAllData()` wipes everything the SDK stored on the device.

For the Google Play Data safety form, the SDK collects App info and performance (Diagnostics). It collects no location and no device IDs, and shares no data with third parties. Events carry the carrier's MCC, which names the SIM's country.

## Cost and limits

- About 50 KB of probe traffic and 170 KB of uploads a day. The SDK adds about 64 KB to a release APK.
- Failover covers only listed hosts on wrapped clients, and only direct connections without a proxy.
- Any HTTP status counts as a working server, 5xx and block pages included.
- IPv6 addresses are passed through without probes.
- The SDK runs in the app's main process only.

## Support

Questions and bugs go to GitHub Issues. Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Run `./gradlew check` before you open a pull request.

## License

MIT. See [LICENSE](LICENSE).
