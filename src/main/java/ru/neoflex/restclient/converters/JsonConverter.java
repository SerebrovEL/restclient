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

/**
 * Утилитарный класс для преобразования объектов в JSON и обратно.
 * Поддерживает примитивные типы, коллекции, массивы, Map'ы и пользовательские объекты.
 */
public class JsonConverter {

  private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  /**
   * Преобразует объект в JSON строку.
   *
   * @param object объект для сериализации
   * @return JSON строка
   * @throws RuntimeException если не удается преобразовать объект
   */
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
      return escapeJsonString(ISO_DATE_TIME_FORMATTER.format((TemporalAccessor) object));
    } else if (object instanceof UUID) {
      return escapeJsonString(object.toString());
    } else {
      return objectToJson(object);
    }
  }

  /**
   * Преобразует пользовательский объект в JSON строку.
   * Рекурсивно обрабатывает все поля объекта, включая наследуемые.
   *
   * @param object объект для преобразования
   * @return JSON строка
   */
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
        
        String fieldName = getJsonFieldName(field);
        
        if (!first) {
          json.append(",");
        }
        first = false;
        
        json.append(escapeJsonString(fieldName))
            .append(":")
            .append(toJson(value));
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to convert object to JSON", e);
      }
    }
    
    json.append("}");
    return json.toString();
  }

  /**
   * Получает имя поля для JSON с учетом аннотации @JsonField.
   *
   * @param field поле класса
   * @return имя поля для JSON
   */
  private static String getJsonFieldName(Field field) {
    String fieldName = field.getName();
    if (field.isAnnotationPresent(JsonField.class)) {
      String customName = field.getAnnotation(JsonField.class).value();
      if (!customName.isEmpty()) {
        fieldName = customName;
      }
    }
    return fieldName;
  }

  /**
   * Преобразует коллекцию или массив в JSON строку.
   *
   * @param object коллекция или массив
   * @return JSON строка массива
   */
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

  /**
   * Преобразует Map в JSON строку.
   *
   * @param map объект Map для преобразования
   * @return JSON строка объекта
   */
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

  /**
   * Преобразует JSON строку в объект указанного типа.
   *
   * @param <T> тип возвращаемого объекта
   * @param json JSON строка
   * @param type тип целевого объекта
   * @return объект указанного типа
   * @throws UnsupportedOperationException если тип не поддерживается
   */
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

  /**
   * Парсит JSON строку в объект LocalDateTime.
   *
   * @param json JSON строка с датой-временем
   * @return объект LocalDateTime
   */
  private static LocalDateTime parseLocalDateTime(String json) {
    String value = json.trim();
    if (value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return LocalDateTime.parse(value, ISO_DATE_TIME_FORMATTER);
  }

  /**
   * Парсит JSON строку в объект UUID.
   *
   * @param json JSON строка с UUID
   * @return объект UUID
   */
  private static UUID parseUUID(String json) {
    String value = json.trim();
    if (value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return UUID.fromString(value);
  }

  /**
   * Парсит JSON строку в пользовательский объект.
   *
   * @param <T> тип целевого объекта
   * @param json JSON строка
   * @param clazz класс целевого объекта
   * @return объект указанного класса
   * @throws RuntimeException если не удается создать или заполнить объект
   */
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
        String fieldName = getJsonFieldName(field);
        
        if (fieldMap.containsKey(fieldName)) {
          String valueStr = fieldMap.get(fieldName);
          Object value = fromJson(valueStr, field.getGenericType());
          field.set(instance, value);
        }
      }
      
      return instance;
    } catch (IllegalAccessException | IllegalArgumentException | InstantiationException 
        | NoSuchMethodException | SecurityException | InvocationTargetException e) {
      throw new RuntimeException("Failed to parse JSON to object", e);
    }
  }

  /**
   * Парсит содержимое JSON объекта в Map имен и значений полей.
   *
   * @param jsonContent содержимое JSON объекта (без внешних фигурных скобок)
   * @return Map имен полей и их JSON значений
   */
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

  /**
   * Парсит JSON строку в коллекцию или массив.
   *
   * @param json JSON строка массива
   * @param clazz класс коллекции или массива
   * @return коллекция или массив объектов
   */
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

  /**
   * Парсит JSON строку в типизированную коллекцию.
   *
   * @param <T> тип элементов коллекции
   * @param json JSON строка массива
   * @param collectionClass класс коллекции
   * @param elementType тип элементов коллекции
   * @return типизированная коллекция
   */
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

  /**
   * Парсит JSON строку в Map.
   *
   * @param json JSON строка объекта
   * @param clazz класс Map
   * @return Map строковых ключей и значений указанного класса
   */
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

  /**
   * Парсит JSON строку в типизированный Map.
   *
   * @param <V> тип значений Map
   * @param json JSON строка объекта
   * @param valueType тип значений Map
   * @return типизированный Map
   */
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

  /**
   * Парсит JSON строку в примитивное значение или строку.
   *
   * @param json JSON строка с примитивным значением
   * @param clazz класс примитивного типа или строки
   * @return примитивное значение или строка
   * @throws UnsupportedOperationException если тип не поддерживается
   */
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

  /**
   * Создает пустую коллекцию указанного типа.
   *
   * @param clazz класс коллекции или массива
   * @return пустая коллекция соответствующего типа
   */
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

  /**
   * Разделяет содержимое JSON массива на отдельные элементы.
   *
   * @param jsonArrayContent содержимое JSON массива (без внешних квадратных скобок)
   * @return список JSON строк элементов
   */
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

  /**
   * Получает все поля класса, включая наследуемые.
   *
   * @param clazz класс для анализа
   * @return список всех полей класса
   */
  private static List<Field> getAllFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();
    while (clazz != null && clazz != Object.class) {
      fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
      clazz = clazz.getSuperclass();
    }
    return fields;
  }

  /**
   * Проверяет, является ли класс примитивным типом или его оберткой.
   *
   * @param clazz класс для проверки
   * @return true если класс примитивный или обертка примитивного типа
   */
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

  /**
   * Экранирует специальные символы в JSON строке.
   *
   * @param str исходная строка
   * @return экранированная JSON строка
   */
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
