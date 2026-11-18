package net.qrator.failoverbeacon.gradle;

import groovy.json.JsonException;
import groovy.json.JsonSlurper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.gradle.api.InvalidUserDataException;

final class JsonValue {
    private final String file;
    private final String path;
    private final boolean present;
    private final Object value;

    private JsonValue(String file, String path, boolean present, Object value) {
        this.file = file;
        this.path = path;
        this.present = present;
        this.value = value;
    }

    static JsonValue parse(File json, String file) {
        try {
            Object value = new JsonSlurper().parse(json, StandardCharsets.UTF_8.name());
            return new JsonValue(file, "", true, value);
        } catch (JsonException e) {
            throw new InvalidUserDataException(file + ": " + e.getMessage(), e);
        }
    }

    boolean isPresent() {
        return present;
    }

    JsonValue get(String key) {
        Map<?, ?> object = object();
        return new JsonValue(file, child(key), object.containsKey(key), object.get(key));
    }

    void allowKeys(String... keys) {
        List<String> allowed = Arrays.asList(keys);
        for (Object key : object().keySet()) {
            if (!allowed.contains(key)) {
                throw new JsonValue(file, child(String.valueOf(key)), true, null)
                        .error("unknown key");
            }
        }
    }

    List<JsonValue> items() {
        List<JsonValue> items = new ArrayList<>();
        if (!present) {
            return items;
        }
        if (!(value instanceof List)) {
            throw error("must be an array");
        }
        List<?> array = (List<?>) value;
        for (int i = 0; i < array.size(); i++) {
            items.add(new JsonValue(file, path + "[" + i + "]", true, array.get(i)));
        }
        return items;
    }

    List<String> strings() {
        List<String> strings = new ArrayList<>();
        for (JsonValue item : items()) {
            strings.add(item.string());
        }
        return strings;
    }

    String string() {
        return (String) required(String.class, "must be a string");
    }

    String string(String absent) {
        return present ? string() : absent;
    }

    int integer() {
        return (Integer) required(Integer.class, "must be an integer");
    }

    int integer(int absent) {
        return present ? integer() : absent;
    }

    long longInteger() {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return (Long) required(Long.class, "must be an integer");
    }

    double number() {
        return ((Number) required(Number.class, "must be a number")).doubleValue();
    }

    boolean bool() {
        return (Boolean) required(Boolean.class, "must be true or false");
    }

    boolean bool(boolean absent) {
        return present ? bool() : absent;
    }

    void check(Runnable rule) {
        check(() -> {
            rule.run();
            return null;
        });
    }

    <T> T check(Supplier<T> rule) {
        try {
            return rule.get();
        } catch (IllegalArgumentException e) {
            throw error(e.getMessage());
        }
    }

    private Map<?, ?> object() {
        return (Map<?, ?>) required(Map.class, "must be an object");
    }

    private Object required(Class<?> type, String problem) {
        if (!present) {
            throw error("is required");
        }
        if (!type.isInstance(value)) {
            throw error(problem);
        }
        return value;
    }

    private String child(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private InvalidUserDataException error(String problem) {
        String where = path.isEmpty() ? "" : path + ": ";
        return new InvalidUserDataException(file + ": " + where + problem);
    }
}
