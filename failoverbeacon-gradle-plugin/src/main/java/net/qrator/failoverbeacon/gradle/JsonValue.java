package net.qrator.failoverbeacon.gradle;

import groovy.json.JsonException;
import groovy.json.JsonSlurper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.InvalidUserDataException;

final class JsonValue {
    private final String file;
    private final String path;
    private final Object value;

    private JsonValue(String file, String path, Object value) {
        this.file = file;
        this.path = path;
        this.value = value;
    }

    static JsonValue parse(File json, String file) {
        try {
            Object value = new JsonSlurper().parse(json, StandardCharsets.UTF_8.name());
            return new JsonValue(file, "", value);
        } catch (JsonException e) {
            throw new InvalidUserDataException(file + ": " + e.getMessage(), e);
        }
    }

    boolean isPresent() {
        return value != null;
    }

    JsonValue get(String key) {
        if (!(value instanceof Map)) {
            throw error("must be an object");
        }
        String childPath = path.isEmpty() ? key : path + "." + key;
        return new JsonValue(file, childPath, ((Map<?, ?>) value).get(key));
    }

    List<JsonValue> items() {
        List<JsonValue> items = new ArrayList<>();
        if (!isPresent()) {
            return items;
        }
        if (!(value instanceof List)) {
            throw error("must be an array");
        }
        List<?> array = (List<?>) value;
        for (int i = 0; i < array.size(); i++) {
            items.add(new JsonValue(file, path + "[" + i + "]", array.get(i)));
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
        if (!isPresent()) {
            throw error("is required");
        }
        if (!(value instanceof String)) {
            throw error("must be a string");
        }
        return (String) value;
    }

    String string(String absent) {
        return isPresent() ? string() : absent;
    }

    int integer(int absent) {
        if (!isPresent()) {
            return absent;
        }
        if (!(value instanceof Integer)) {
            throw error("must be an integer");
        }
        return (Integer) value;
    }

    boolean bool(boolean absent) {
        if (!isPresent()) {
            return absent;
        }
        if (!(value instanceof Boolean)) {
            throw error("must be true or false");
        }
        return (Boolean) value;
    }

    private InvalidUserDataException error(String problem) {
        String where = path.isEmpty() ? "the root" : path;
        return new InvalidUserDataException(file + ": " + where + " " + problem);
    }
}
