package ru.neoflex.restclient.converters;

import ru.neoflex.restclient.annotations.JsonField;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class JsonConverter {

    public static String toJson(Object object) {
        if (object == null) {
            return "null";
        }

        Class<?> clazz = object.getClass();

        if (isPrimitiveOrWrapper(clazz) || clazz == String.class) {
            return escapeJsonString(String.valueOf(object));
        } else if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)) {
            return collectionToJson(object);
        } else if (Map.class.isAssignableFrom(clazz)) {
            return mapToJson((Map<?, ?>) object);
        } else if (object instanceof LocalDateTime) {
            return escapeJsonString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format((TemporalAccessor) object));
        } else if (object instanceof UUID) {
            return escapeJsonString(object.toString());
        } else {
            return objectToJson(object);
        }
    }

    private static String objectToJson(Object object) {
        Class<?> clazz = object.getClass();
        StringBuilder json = new StringBuilder("{");
        List<Field> fields = getAllFields(clazz);
        boolean first = true;
        for (Field field : fields) {
            field.setAccessible(true);
            try {
                Object value = field.get(object);
                if (value == null) {
                    continue;
                }
                String fieldName = field.getName();
                if (field.isAnnotationPresent(JsonField.class)) {
                    String customName = field.getAnnotation(JsonField.class).value();
                    if (!customName.isEmpty()) {
                        fieldName = customName;
                    }
                }
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append(escapeJsonString(fieldName)).append(":").append(toJson(value));
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to convert object to JSON", e);
            }
        }
        json.append("}");
        return json.toString();
    }

    private static String collectionToJson(Object object) {
        Collection<?> collection;
        if (object.getClass().isArray()) {
            collection = Arrays.asList((Object[]) object);
        } else {
            collection = (Collection<?>) object;
        }
        return "[" + collection.stream()
                .map(JsonConverter::toJson)
                .collect(Collectors.joining(",")) + "]";
    }

    private static String mapToJson(Map<?, ?> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append(escapeJsonString(String.valueOf(entry.getKey())))
                    .append(":")
                    .append(toJson(entry.getValue()));
        }
        json.append("}");
        return json.toString();
    }

    @SuppressWarnings({"unchecked", "unchecked"})
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.trim().isEmpty() || "null".equals(json.trim())) {
            return null;
        }
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (isPrimitiveOrWrapper(clazz) || clazz == String.class) {
                return (T) parsePrimitive(json, clazz);
            } else if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)) {
                return (T) parseCollection(json, clazz);
            } else if (Map.class.isAssignableFrom(clazz)) {
                return (T) parseMap(json, clazz);
            } else if (clazz == LocalDateTime.class) {
                return (T) parseLocalDateTime(json);
            } else if (clazz == UUID.class) {
                return (T) parseUUID(json);
            } else {
                return (T) parseObject(json, clazz);
            }
        } else if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType == List.class || rawType == Collection.class) {
                Type elementType = ((ParameterizedType) type).getActualTypeArguments()[0];
                return (T) parseGenericCollection(json, List.class, elementType);
            } else if (rawType == Map.class) {
                Type keyType = ((ParameterizedType) type).getActualTypeArguments()[0];
                Type valueType = ((ParameterizedType) type).getActualTypeArguments()[1];
                if (keyType != String.class) {
                    throw new UnsupportedOperationException("Only String keys are supported in Maps");
                }
                return (T) parseGenericMap(json, valueType);
            }
        }
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }

    private static LocalDateTime parseLocalDateTime(String json) {
        String value = json.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static UUID parseUUID(String json) {
        String value = json.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return UUID.fromString(value);
    }

    private static <T> T parseObject(String json, Class<T> clazz) {
        try {
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                throw new IllegalArgumentException("Invalid JSON object: " + json);
            }
            String content = json.substring(1, json.length() - 1).trim();
            if (content.isEmpty()) {
                return clazz.getDeclaredConstructor().newInstance();
            }
            T instance = clazz.getDeclaredConstructor().newInstance();
            Map<String, String> fieldMap = parseJsonFields(content);
            for (Field field : getAllFields(clazz)) {
                field.setAccessible(true);
                String fieldName = field.getName();
                if (field.isAnnotationPresent(JsonField.class)) {
                    String customName = field.getAnnotation(JsonField.class).value();
                    if (!customName.isEmpty()) {
                        fieldName = customName;
                    }
                }
                if (fieldMap.containsKey(fieldName)) {
                    String valueStr = fieldMap.get(fieldName);
                    Object value = fromJson(valueStr, field.getGenericType());
                    field.set(instance, value);
                }
            }
            return instance;
        } catch (IllegalAccessException | IllegalArgumentException | InstantiationException | NoSuchMethodException | SecurityException | InvocationTargetException e) {
            throw new RuntimeException("Failed to parse JSON to object", e);
        }
    }

    private static Map<String, String> parseJsonFields(String jsonContent) {
        Map<String, String> fieldMap = new HashMap<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        String currentKey = null;
        boolean inString = false;
        boolean escape = false;
        for (char c : jsonContent.toCharArray()) {
            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                current.append(c);
                continue;
            }
            if (c == '"') {
                inString = !inString;
                current.append(c);
                continue;
            }
            if (!inString && (c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == '}' || c == ']')) {
                depth--;
            }
            if (!inString && depth == 0 && c == ',' && currentKey != null) {
                fieldMap.put(currentKey, current.toString());
                current.setLength(0);
                currentKey = null;
                continue;
            }
            if (!inString && depth == 0 && c == ':' && currentKey == null) {
                currentKey = current.toString().trim();
                if (currentKey.startsWith("\"") && currentKey.endsWith("\"")) {
                    currentKey = currentKey.substring(1, currentKey.length() - 1);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (currentKey != null && current.length() > 0) {
            fieldMap.put(currentKey, current.toString());
        }
        return fieldMap;
    }

    private static Object parseCollection(String json, Class<?> clazz) {
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new IllegalArgumentException("Invalid JSON array: " + json);
        }
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) {
            return createEmptyCollection(clazz);
        }
        List<String> elements = splitJsonElements(content);
        Collection<Object> collection = createEmptyCollection(clazz);
        for (String element : elements) {
            collection.add(fromJson(element, Object.class));
        }
        if (clazz.isArray()) {
            return collection.toArray((Object[]) Array.newInstance(clazz.getComponentType(), collection.size()));
        }
        return collection;
    }

    @SuppressWarnings("unchecked")
    private static <T> Collection<T> parseGenericCollection(String json, Class<?> collectionClass, Type elementType) {
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new IllegalArgumentException("Invalid JSON array: " + json);
        }
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) {
            return (Collection<T>) createEmptyCollection(collectionClass);
        }
        List<String> elements = splitJsonElements(content);
        Collection<T> collection = (Collection<T>) createEmptyCollection(collectionClass);
        for (String element : elements) {
            collection.add((T) fromJson(element, elementType));
        }
        return collection;
    }

    private static Map<String, Class<?>> parseMap(String json, Class<?> clazz) {
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("Invalid JSON object: " + json);
        }
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> fieldMap = parseJsonFields(content);
        Map<String, Class<?>> result = new HashMap<>();
        for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
            result.put(entry.getKey(), fromJson(entry.getValue(), clazz));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> parseGenericMap(String json, Type valueType) {
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("Invalid JSON object: " + json);
        }
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> fieldMap = parseJsonFields(content);
        Map<String, V> result = new HashMap<>();
        for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
            result.put(entry.getKey(), (V) fromJson(entry.getValue(), valueType));
        }
        return result;
    }

    private static Object parsePrimitive(String json, Class<?> clazz) {
        String value = json.trim();
        if (clazz == String.class) {
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        } else if (clazz == Integer.class || clazz == int.class) {
            return Integer.valueOf(value);
        } else if (clazz == Long.class || clazz == long.class) {
            return Long.valueOf(value);
        } else if (clazz == Double.class || clazz == double.class) {
            return Double.valueOf(value);
        } else if (clazz == Float.class || clazz == float.class) {
            return Float.valueOf(value);
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            return Boolean.valueOf(value);
        } else if (clazz == Byte.class || clazz == byte.class) {
            return Byte.valueOf(value);
        } else if (clazz == Short.class || clazz == short.class) {
            return Short.valueOf(value);
        } else if (clazz == Character.class || clazz == char.class) {
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() == 3) {
                return value.charAt(1);
            }
            throw new IllegalArgumentException("Invalid character value: " + value);
        }
        throw new UnsupportedOperationException("Unsupported primitive type: " + clazz);
    }

    private static Collection<Object> createEmptyCollection(Class<?> clazz) {
        if (clazz.isArray()) {
            return new ArrayList<>();
        } else if (List.class.isAssignableFrom(clazz)) {
            return new ArrayList<>();
        } else if (Set.class.isAssignableFrom(clazz)) {
            return new HashSet<>();
        } else if (Queue.class.isAssignableFrom(clazz)) {
            return new LinkedList<>();
        } else {
            return new ArrayList<>();
        }
    }

    private static List<String> splitJsonElements(String jsonArrayContent) {
        List<String> elements = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        StringBuilder current = new StringBuilder();
        for (char c : jsonArrayContent.toCharArray()) {
            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                current.append(c);
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            if (!inString && (c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == '}' || c == ']')) {
                depth--;
            }
            if (!inString && depth == 0 && c == ',') {
                elements.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            elements.add(current.toString().trim());
        }
        return elements;
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    private static boolean isPrimitiveOrWrapper(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz == Integer.class
                || clazz == Long.class
                || clazz == Double.class
                || clazz == Float.class
                || clazz == Boolean.class
                || clazz == Byte.class
                || clazz == Short.class
                || clazz == Character.class;
    }

    private static String escapeJsonString(String str) {
        if (str == null) {
            return "null";
        }
        return "\"" + str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
