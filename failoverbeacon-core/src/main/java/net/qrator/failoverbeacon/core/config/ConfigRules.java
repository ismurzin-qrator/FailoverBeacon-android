package net.qrator.failoverbeacon.core.config;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ConfigRules {
    private static final String FORBIDDEN_DOMAIN_CHARS = "#%/:<>?@[\\]^|";
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int IPV4_OCTETS = 4;
    private static final int MAX_OCTET_DIGITS = 3;
    private static final int MAX_OCTET = 255;
    private static final int MAX_PORT = 65535;
    private static final int MIN_BODY_PROBE_BYTES = 1024;
    private static final int MAX_BODY_PROBE_BYTES = 1024 * 1024;
    private static final long MIN_UPLOAD_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long MAX_UPLOAD_PERIOD_MILLIS = TimeUnit.HOURS.toMillis(8);
    private static final long MIN_REFRESH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long MAX_REFRESH_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private ConfigRules() {}

    public static void checkBeacons(List<String> beacons) {
        if (beacons.isEmpty()) {
            throw new IllegalArgumentException("beacons must not be empty");
        }
        for (String url : beacons) {
            checkBeaconUrl(url);
        }
    }

    public static void checkBeaconsRequired(List<String> beacons) {
        if (beacons.isEmpty()) {
            throw new IllegalArgumentException("beacons are required");
        }
    }

    public static String checkBlockedHost(String host) {
        String name = canonicalHost(host);
        if (name == null) {
            throw new IllegalArgumentException(
                    "blockedHosts must contain host names without port, was \"" + host + "\"");
        }
        return name;
    }

    public static double checkSampleRate(String name, double rate) {
        if (!(rate >= 0.0 && rate <= 1.0)) {
            throw new IllegalArgumentException(name + " must be in [0.0, 1.0], was " + rate);
        }
        return rate;
    }

    public static void checkDistinctFailoverHosts(List<String> hosts) {
        Set<String> names = new HashSet<>();
        for (String host : hosts) {
            if (!names.add(host)) {
                throw new IllegalArgumentException("failoverHosts contains \"" + host + "\" twice");
            }
        }
    }

    public static String checkFailoverHost(String host) {
        String name = canonicalHost(host);
        if (name == null) {
            throw new IllegalArgumentException(
                    "host must be a host name without scheme, port or path, was \"" + host + "\"");
        }
        if (endsInNumber(name)) {
            throw new IllegalArgumentException(
                    "host must be a name, not an IP address, was \"" + host + "\"");
        }
        return name;
    }

    public static int checkPort(int port) {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be in 1.." + MAX_PORT + ", was " + port);
        }
        return port;
    }

    public static Inet4Address checkAdditionalAddress(String address, String host) {
        Inet4Address parsed = parseIpv4(address, host);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "additionalAddresses must contain IPv4 addresses like 192.0.2.10, was \""
                            + address
                            + "\"");
        }
        return parsed;
    }

    public static void checkBodyProbeNeedsTcpProbe(boolean tcpProbe, boolean bodyProbe) {
        if (bodyProbe && !tcpProbe) {
            throw new IllegalArgumentException("bodyProbe needs tcpProbe");
        }
    }

    public static String checkBodyProbePath(String path) {
        boolean valid = path.startsWith("/");
        for (int i = 0; valid && i < path.length(); i++) {
            char c = path.charAt(i);
            valid = c >= '!' && c <= '~' && c != '#';
        }
        if (!valid) {
            throw new IllegalArgumentException(
                    "path must start with / and have only visible ASCII, without a fragment, was \""
                            + path
                            + "\"");
        }
        return path;
    }

    public static int checkBodyProbeMaxBytes(int maxBytes) {
        if (maxBytes < MIN_BODY_PROBE_BYTES || maxBytes > MAX_BODY_PROBE_BYTES) {
            throw new IllegalArgumentException(
                    "maxBytes must be in "
                            + MIN_BODY_PROBE_BYTES
                            + ".."
                            + MAX_BODY_PROBE_BYTES
                            + ", was "
                            + maxBytes);
        }
        return maxBytes;
    }

    public static long checkUploadPeriod(long period, TimeUnit unit) {
        long millis = unit.toMillis(period);
        if (millis < MIN_UPLOAD_PERIOD_MILLIS || millis > MAX_UPLOAD_PERIOD_MILLIS) {
            throw new IllegalArgumentException(
                    "uploadPeriod must be from 1 second to 8 hours, was " + period + " " + unit);
        }
        return millis;
    }

    public static long checkDiskQueueBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("diskQueueBytes must be > 0, was " + bytes);
        }
        return bytes;
    }

    public static int checkMemoryQueueSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("memoryQueueSize must be > 0, was " + size);
        }
        return size;
    }

    public static long checkRefreshInterval(long interval, TimeUnit unit) {
        long millis = unit.toMillis(interval);
        if (millis < MIN_REFRESH_INTERVAL_MILLIS || millis > MAX_REFRESH_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(
                    "refreshInterval must be from 1 minute to 1 hour, was "
                            + interval
                            + " "
                            + unit);
        }
        return millis;
    }

    private static void checkBeaconUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid beacon URL: " + url, e);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Beacon URL must not contain user info");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Beacon URL must use https: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Beacon URL must have a host: " + url);
        }
    }

    private static String canonicalHost(String host) {
        String ascii;
        try {
            ascii = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (ascii.length() > MAX_DOMAIN_LENGTH) {
            return null;
        }
        for (int i = 0; i < ascii.length(); i++) {
            char c = ascii.charAt(i);
            if (c <= ' ' || c >= '\u007f' || FORBIDDEN_DOMAIN_CHARS.indexOf(c) >= 0) {
                return null;
            }
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                return null;
            }
        }
        return ascii;
    }

    // URL Standard, "ends in a number checker": URL parsers read such a host as IPv4.
    private static boolean endsInNumber(String host) {
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (last.isEmpty()) {
            return false;
        }
        if (isDigits(last)) {
            return true;
        }
        if (!last.startsWith("0x")) {
            return false;
        }
        for (int i = 2; i < last.length(); i++) {
            char c = last.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static Inet4Address parseIpv4(String address, String host) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != IPV4_OCTETS) {
            return null;
        }
        byte[] bytes = new byte[IPV4_OCTETS];
        for (int i = 0; i < IPV4_OCTETS; i++) {
            String octet = octets[i];
            if (!isOctet(octet)) {
                return null;
            }
            bytes[i] = (byte) Integer.parseInt(octet);
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(host, bytes);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isOctet(String octet) {
        return !octet.isEmpty()
                && octet.length() <= MAX_OCTET_DIGITS
                && isDigits(octet)
                && (octet.length() == 1 || octet.charAt(0) != '0')
                && Integer.parseInt(octet) <= MAX_OCTET;
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
