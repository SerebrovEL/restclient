package ru.neoflex.restclient;

import ru.neoflex.restclient.annotations.BaseUrl;
import ru.neoflex.restclient.annotations.Body;
import ru.neoflex.restclient.annotations.DELETE;
import ru.neoflex.restclient.annotations.GET;
import ru.neoflex.restclient.annotations.HEAD;
import ru.neoflex.restclient.annotations.Header;
import ru.neoflex.restclient.annotations.Headers;
import ru.neoflex.restclient.annotations.PATCH;
import ru.neoflex.restclient.annotations.POST;
import ru.neoflex.restclient.annotations.PUT;
import ru.neoflex.restclient.annotations.Path;
import ru.neoflex.restclient.annotations.Query;
import ru.neoflex.restclient.converters.JsonConverter;
import ru.neoflex.restclient.interceptors.RequestInterceptor;
import ru.neoflex.restclient.interceptors.ResponseInterceptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Главный класс REST клиента для создания и выполнения HTTP запросов.
 * Поддерживает декларативное описание REST API через аннотации и динамическое создание прокси-объектов.
 * 
 * <p>Основные возможности:
 * <ul>
 *   <li>Поддержка всех основных HTTP методов (GET, POST, PUT, DELETE, PATCH, HEAD)</li>
 *   <li>Параметры пути, запроса и заголовков через аннотации</li>
 *   <li>Интерцепторы для модификации запросов и обработки ответов</li>
 *   <li>Поддержка повторных попыток (retry mechanism)</li>
 *   <li>Ограничение скорости запросов (rate limiting)</li>
 *   <li>Настраиваемые таймауты соединения</li>
 *   <li>Прокси-поддержка</li>
 *   <li>SSL/TLS настройки, включая игнорирование сертификатов</li>
 *   <li>Кастомные конвертеры для сериализации/десериализации</li>
 * </ul>
 * 
 * <p>Пример использования:
 * <pre>{@code
 * public interface UserService {
 *   @GET("/users/{id}")
 *   User getUser(@Path("id") String id, @Header("Authorization") String token);
 *   
 *   @POST("/users")
 *   User createUser(@Body User user);
 * }
 * 
 * RestClient client = new RestClient.Builder()
 *     .baseUrl("https://api.example.com")
 *     .connectTimeout(5000)
 *     .readTimeout(10000)
 *     .build();
 * 
 * UserService service = client.create(UserService.class);
 * User user = service.getUser("123", "Bearer token");
 * }</pre>
 */
public class RestClient {

  private final String baseUrl;
  private final List<RequestInterceptor> requestInterceptors;
  private final List<ResponseInterceptor> responseInterceptors;
  private final Map<Class<?>, Object> serviceCache;
  private final SSLContext sslContext;
  private final boolean ignoreSSL;
  private final int connectTimeout;
  private final int readTimeout;
  private final int writeTimeout;
  private final int maxRetries;
  private final boolean followRedirects;
  private final java.net.Proxy proxy;
  private final RateLimiter rateLimiter;
  private final Converter converter;

  /**
   * Приватный конструктор для использования через Builder.
   * Инициализирует все настройки клиента из билдера.
   *
   * @param builder билдер для конфигурации клиента
   */
  private RestClient(Builder builder) {
    this.baseUrl = builder.baseUrl;
    this.requestInterceptors = Collections.unmodifiableList(builder.requestInterceptors);
    this.responseInterceptors = Collections.unmodifiableList(builder.responseInterceptors);
    this.serviceCache = new ConcurrentHashMap<>();
    this.sslContext = builder.sslContext;
    this.ignoreSSL = builder.ignoreSSL;
    this.connectTimeout = builder.connectTimeout;
    this.readTimeout = builder.readTimeout;
    this.writeTimeout = builder.writeTimeout;
    this.maxRetries = builder.maxRetries;
    this.followRedirects = builder.followRedirects;
    this.proxy = builder.proxy;
    this.rateLimiter = builder.rateLimiter;
    this.converter = builder.converter != null ? builder.converter : new JsonConverterAdapter();
  }

  /**
   * Интерфейс конвертера для сериализации и десериализации данных.
   * Позволяет использовать кастомные форматы данных вместо JSON по умолчанию.
   */
  public interface Converter {

    /**
     * Определяет Content-Type для тела запроса на основе типа данных.
     *
     * @param body тело запроса
     * @return строка Content-Type (например, "application/json", "application/xml")
     */
    String contentType(Object body);

    /**
     * Сериализует объект в байтовый массив для отправки в теле запроса.
     *
     * @param body объект для сериализации
     * @return байтовый массив с сериализованными данными
     * @throws IOException если произошла ошибка сериализации
     */
    byte[] write(Object body) throws IOException;

    /**
     * Десериализует байтовый массив ответа в объект указанного типа.
     *
     * @param <T> тип возвращаемого объекта
     * @param data байтовый массив с данными ответа
     * @param type тип целевого объекта
     * @return десериализованный объект
     * @throws IOException если произошла ошибка десериализации
     */
    <T> T read(byte[] data, Type type) throws IOException;
  }

  /**
   * Адаптер конвертера по умолчанию, использующий JSON формат.
   * Использует JsonConverter для сериализации/десериализации.
   */
  private static class JsonConverterAdapter implements Converter {

    /**
     * Всегда возвращает "application/json" как Content-Type.
     *
     * @param body тело запроса (не используется)
     * @return "application/json"
     */
    @Override
    public String contentType(Object body) {
      return "application/json";
    }

    /**
     * Сериализует объект в JSON строку и преобразует в байты UTF-8.
     *
     * @param body объект для сериализации
     * @return байтовый массив с JSON представлением объекта
     * @throws IOException если произошла ошибка преобразования
     */
    @Override
    public byte[] write(Object body) throws IOException {
      return JsonConverter.toJson(body).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Десериализует байтовый массив из JSON в объект указанного типа.
     *
     * @param <T> тип возвращаемого объекта
     * @param data байтовый массив с JSON данными
     * @param type тип целевого объекта
     * @return десериализованный объект
     * @throws IOException если произошла ошибка десериализации
     */
    @Override
    public <T> T read(byte[] data, Type type) throws IOException {
      return JsonConverter.fromJson(new String(data, StandardCharsets.UTF_8), type);
    }
  }

  /**
   * Простая реализация ограничителя скорости запросов.
   * Ограничивает количество запросов в минуту.
   */
  public static class SimpleRateLimiter implements RateLimiter {

    private final int maxRequestsPerMinute;
    private final Object lock = new Object();
    private long lastResetTime;
    private int requestCount;

    /**
     * Создает ограничитель скорости с указанным лимитом запросов.
     *
     * @param maxRequestsPerMinute максимальное количество запросов в минуту
     */
    public SimpleRateLimiter(int maxRequestsPerMinute) {
      this.maxRequestsPerMinute = maxRequestsPerMinute;
      this.lastResetTime = System.currentTimeMillis();
      this.requestCount = 0;
    }

    /**
     * Получает разрешение на выполнение запроса.
     * Блокирует выполнение, если лимит превышен, до сброса счетчика.
     *
     * @throws InterruptedException если поток был прерван во время ожидания
     */
    @Override
    public void acquire() throws InterruptedException {
      synchronized (lock) {
        // Сброс счетчика если прошла минута
        if (System.currentTimeMillis() - lastResetTime > 60000) {
          requestCount = 0;
          lastResetTime = System.currentTimeMillis();
        }

        // Ожидание если лимит исчерпан
        while (requestCount >= maxRequestsPerMinute) {
          long waitTime = 60000 - (System.currentTimeMillis() - lastResetTime);
          if (waitTime > 0) {
            lock.wait(waitTime);
            requestCount = 0;
            lastResetTime = System.currentTimeMillis();
          }
        }
        requestCount++;
      }
    }
  }

  /**
   * Создает прокси-объект для указанного интерфейса сервиса.
   * Использует кэширование для избежания повторного создания одинаковых сервисов.
   *
   * @param <T> тип интерфейса сервиса
   * @param service класс интерфейса сервиса
   * @return прокси-объект, реализующий интерфейс сервиса
   * @throws IllegalArgumentException если service не является интерфейсом
   */
  @SuppressWarnings("unchecked")
  public <T> T create(Class<T> service) {
    if (!service.isInterface()) {
      throw new IllegalArgumentException("Service must be an interface");
    }
    return (T) serviceCache.computeIfAbsent(service, this::createService);
  }

  /**
   * Создает динамический прокси для интерфейса сервиса.
   * Все вызовы методов прокси перенаправляются в processMethod.
   *
   * @param <T> тип интерфейса сервиса
   * @param service класс интерфейса сервиса
   * @return прокси-объект
   */
  private <T> Object createService(Class<T> service) {
    return Proxy.newProxyInstance(
        service.getClassLoader(),
        new Class<?>[]{service},
        (proxy, method, args) -> processMethod(method, args));
  }

  /**
   * Обрабатывает вызов метода сервиса: парсит аннотации, строит запрос, выполняет его.
   *
   * <p>Алгоритм обработки:
   * <ol>
   *   <li>Определяет HTTP метод и путь из аннотаций метода</li>
   *   <li>Определяет базовый URL из аннотации класса или настроек клиента</li>
   *   <li>Парсит параметры метода (path, query, header, body)</li>
   *   <li>Обрабатывает статические заголовки из аннотации @Headers</li>
   *   <li>Заменяет path-параметры в URL</li>
   *   <li>Добавляет query-параметры к URL</li>
   *   <li>Выполняет запрос с поддержкой повторных попыток</li>
   * </ol>
   *
   * @param method вызываемый метод сервиса
   * @param args аргументы метода
   * @return результат выполнения метода (десериализованный ответ)
   * @throws Exception если произошла ошибка при выполнении запроса
   */
  private Object processMethod(Method method, Object[] args) throws Exception {
    // 1. Определение HTTP метода и пути
    String httpMethod = null;
    String path = "";
    
    if (method.isAnnotationPresent(GET.class)) {
      httpMethod = "GET";
      path = method.getAnnotation(GET.class).value();
    } else if (method.isAnnotationPresent(POST.class)) {
      httpMethod = "POST";
      path = method.getAnnotation(POST.class).value();
    } else if (method.isAnnotationPresent(PUT.class)) {
      httpMethod = "PUT";
      path = method.getAnnotation(PUT.class).value();
    } else if (method.isAnnotationPresent(DELETE.class)) {
      httpMethod = "DELETE";
      path = method.getAnnotation(DELETE.class).value();
    } else if (method.isAnnotationPresent(HEAD.class)) {
      httpMethod = "HEAD";
      path = method.getAnnotation(HEAD.class).value();
    } else if (method.isAnnotationPresent(PATCH.class)) {
      httpMethod = "PATCH";
      path = method.getAnnotation(PATCH.class).value();
    }
    
    if (httpMethod == null) {
      throw new RuntimeException("HTTP method annotation is required");
    }

    // 2. Определение базового URL
    String baseUrl = this.baseUrl;
    if (method.getDeclaringClass().isAnnotationPresent(BaseUrl.class)) {
      baseUrl = method.getDeclaringClass().getAnnotation(BaseUrl.class).value();
    }
    if (baseUrl == null || baseUrl.isEmpty()) {
      throw new RuntimeException("Base URL is not specified");
    }

    // 3. Парсинг параметров метода
    Map<String, String> pathParams = new HashMap<>();
    Map<String, String> queryParams = new HashMap<>();
    Map<String, String> headers = new HashMap<>();
    Object body = null;
    String contentType = "application/json";

    Annotation[][] parameterAnnotations = method.getParameterAnnotations();
    for (int i = 0; i < parameterAnnotations.length; i++) {
      for (Annotation annotation : parameterAnnotations[i]) {
        if (annotation instanceof Path) {
          pathParams.put(((Path) annotation).value(), String.valueOf(args[i]));
        } else if (annotation instanceof Query) {
          String value = String.valueOf(args[i]);
          if (value != null && !value.isEmpty()) {
            queryParams.put(((Query) annotation).value(), value);
          }
        } else if (annotation instanceof Header) {
          headers.put(((Header) annotation).value(), String.valueOf(args[i]));
        } else if (annotation instanceof Body) {
          body = args[i];
          if (body instanceof byte[] || body instanceof InputStream) {
            contentType = "application/octet-stream";
          } else {
            contentType = converter.contentType(body);
          }
        }
      }
    }

    // 4. Установка Content-Type по умолчанию
    if (!headers.containsKey("Content-Type")) {
      headers.put("Content-Type", contentType);
    }

    // 5. Обработка статических заголовков
    if (method.isAnnotationPresent(Headers.class)) {
      String[] headerValues = method.getAnnotation(Headers.class).value();
      for (String header : headerValues) {
        String[] parts = header.split(":", 2);
        if (parts.length == 2) {
          headers.put(parts[0].trim(), parts[1].trim());
        }
      }
    }

    // 6. Замена path-параметров в URL
    for (Map.Entry<String, String> entry : pathParams.entrySet()) {
      path = path.replace("{" + entry.getKey() + "}", entry.getValue());
    }

    // 7. Построение полного URL с query-параметрами
    String urlStr = joinUrls(baseUrl, path);
    if (!queryParams.isEmpty()) {
      urlStr += "?" + buildQueryString(queryParams);
    }

    // 8. Выполнение запроса с поддержкой retry
    Exception lastException = null;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return executeRequest(httpMethod, urlStr, headers, body, method.getGenericReturnType());
      } catch (Exception e) {
        lastException = e;
        if (attempt == maxRetries) {
          break;
        }
        try {
          Thread.sleep(100 * (long) Math.pow(2, attempt)); // Экспоненциальная backoff задержка
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Request interrupted", ie);
        }
      }
    }
    throw new RuntimeException("Request failed after " + maxRetries + " retries", lastException);
  }

  /**
   * Соединяет базовый URL и путь, обрабатывая лишние слэши.
   * Удаляет trailing слэши из baseUrl и leading слэши из path.
   *
   * @param baseUrl базовый URL
   * @param path путь относительно базового URL
   * @return объединенный URL
   */
  private String joinUrls(String baseUrl, String path) {
    if (baseUrl == null || baseUrl.isEmpty()) {
      return path;
    }
    if (path == null || path.isEmpty()) {
      return baseUrl;
    }

    // Удаление trailing слэшей из baseUrl
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    // Удаление leading слэшей из path
    while (path.startsWith("/")) {
      path = path.substring(1);
    }

    return baseUrl + "/" + path;
  }

  /**
   * Строит строку query-параметров для URL.
   * Кодирует имена и значения параметров в UTF-8, пропускает null и пустые значения.
   *
   * @param params Map параметров запроса
   * @return строка query-параметров
   * @throws UnsupportedEncodingException если UTF-8 кодирование не поддерживается
   */
  private String buildQueryString(Map<String, String> params) throws UnsupportedEncodingException {
    StringBuilder result = new StringBuilder();
    boolean first = true;
    
    for (Map.Entry<String, String> entry : params.entrySet()) {
      String value = entry.getValue();
      if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) {
        continue;
      }
      
      if (!first) {
        result.append("&");
      }
      first = false;
      
      result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name()));
      result.append("=");
      result.append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
    }
    
    return result.toString();
  }

  /**
   * Выполняет HTTP запрос с учетом всех настроек клиента.
   * 
   * <p>Алгоритм выполнения:
   * <ol>
   *   <li>Применяет ограничитель скорости (если настроен)</li>
   *   <li>Обрабатывает редиректы (до 5 раз)</li>
   *   <li>Создает и настраивает HTTP соединение</li>
   *   <li>Применяет SSL настройки для HTTPS</li>
   *   <li>Устанавливает таймауты и заголовки</li>
   *   <li>Применяет интерцепторы запросов</li>
   *   <li>Отправляет тело запроса (если есть)</li>
   *   <li>Обрабатывает ответ в зависимости от типа возвращаемого значения</li>
   *   <li>Применяет интерцепторы ответов</li>
   *   <li>Десериализует ответ или возвращает сырые данные</li>
   * </ol>
   *
   * @param method HTTP метод (GET, POST, etc.)
   * @param urlStr полный URL запроса
   * @param headers заголовки запроса
   * @param body тело запроса
   * @param returnType тип возвращаемого значения метода
   * @return результат выполнения запроса
   * @throws Exception если произошла ошибка при выполнении запроса
   */
  private Object executeRequest(String method, String urlStr, Map<String, String> headers, 
                               Object body, Type returnType) throws Exception {
    // 1. Применение ограничителя скорости
    if (rateLimiter != null) {
      try {
        rateLimiter.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Request interrupted by rate limiter", e);
      }
    }

    int redirectCount = 0;
    final int maxRedirects = 5;
    HttpURLConnection connection = null;
    Exception lastException = null;

    // 2. Обработка редиректов
    while (redirectCount < maxRedirects) {
      try {
        URL url = new URL(urlStr);
        
        // 3. Создание соединения (через прокси если настроено)
        if (proxy != null) {
          connection = (HttpURLConnection) url.openConnection(proxy);
        } else {
          connection = (HttpURLConnection) url.openConnection();
        }

        // 4. Настройка SSL для HTTPS соединений
        if ("https".equalsIgnoreCase(url.getProtocol()) && connection instanceof HttpsURLConnection) {
          HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
          if (ignoreSSL && sslContext != null) {
            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
            httpsConnection.setHostnameVerifier((hostname, session) -> true);
          }
        }

        // 5. Базовая настройка соединения
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        
        if (body instanceof byte[]) {
          connection.setFixedLengthStreamingMode(((byte[]) body).length);
        }
        
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setRequestMethod(method);

        // 6. Установка заголовков
        for (Map.Entry<String, String> entry : headers.entrySet()) {
          connection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        // 7. Применение интерцепторов запросов
        for (RequestInterceptor interceptor : requestInterceptors) {
          interceptor.intercept(connection);
        }

        // 8. Отправка тела запроса
        if (body != null) {
          connection.setDoOutput(true);
          long startTime = System.currentTimeMillis();
          
          try (OutputStream os = connection.getOutputStream()) {
            if (body instanceof byte[]) {
              os.write((byte[]) body);
            } else if (body instanceof InputStream) {
              InputStream is = (InputStream) body;
              byte[] buffer = new byte[8192];
              int bytesRead;
              
              while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                
                // Проверка таймаута записи
                if (System.currentTimeMillis() - startTime > writeTimeout) {
                  throw new IOException("Write timeout exceeded");
                }
              }
            } else {
              byte[] bytes = converter.write(body);
              os.write(bytes);
            }
          }
        }

        // 9. Получение кода ответа
        int responseCode = connection.getResponseCode();

        // 10. Обработка ручных редиректов (если автоматические отключены)
        if (!followRedirects && (responseCode == HttpURLConnection.HTTP_MOVED_PERM
            || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
            || responseCode == HttpURLConnection.HTTP_SEE_OTHER)) {
          String location = connection.getHeaderField("Location");
          if (location != null) {
            URL redirectUrl = new URL(new URL(urlStr), location);
            urlStr = redirectUrl.toString();
            redirectCount++;
            connection.disconnect();
            continue;
          }
        }

        // 11. Обработка ответа в зависимости от типа возвращаемого значения
        if (returnType == Void.class || returnType == void.class) {
          return null;
        } else if (returnType == byte[].class) {
          try (InputStream is = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (is == null) {
              return new byte[0];
            }
            return readBytes(is);
          }
        } else if (returnType == InputStream.class) {
          return responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        } else {
          byte[] responseBytes;
          try (InputStream is = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            responseBytes = is != null ? readBytes(is) : new byte[0];
          }
          
          String response = new String(responseBytes, getCharset(connection));

          // 12. Применение интерцепторов ответов
          for (ResponseInterceptor interceptor : responseInterceptors) {
            response = interceptor.intercept(responseCode, response);
          }

          // 13. Обработка успешных ответов
          if (responseCode >= 200 && responseCode < 300) {
            if (returnType == String.class) {
              return response;
            } else {
              return converter.read(responseBytes, returnType);
            }
          } else {
            throw new RuntimeException("HTTP error code: " + responseCode + ", response: " + response);
          }
        }
      } catch (Exception e) {
        lastException = e;
        if (connection != null) {
          connection.disconnect();
        }
        throw e;
      }
    }
    throw new RuntimeException("Maximum redirects exceeded", lastException);
  }

  /**
   * Определяет кодировку ответа из заголовков соединения.
   * Ищет charset в Content-Type, по умолчанию использует UTF-8.
   *
   * @param connection HTTP соединение
   * @return кодировка ответа
   */
  private Charset getCharset(HttpURLConnection connection) {
    String contentType = connection.getContentType();
    if (contentType != null) {
      String[] parts = contentType.split(";");
      for (String part : parts) {
        if (part.trim().startsWith("charset=")) {
          String charsetName = part.trim().substring(8);
          try {
            return Charset.forName(charsetName);
          } catch (Exception e) {
            // Используем UTF-8 по умолчанию при ошибке
          }
        }
      }
    }
    return StandardCharsets.UTF_8;
  }

  /**
   * Читает все байты из InputStream в массив.
   * Использует буферизованное чтение для эффективности.
   *
   * @param is InputStream для чтения
   * @return массив байтов
   * @throws IOException если произошла ошибка чтения
   */
  private byte[] readBytes(InputStream is) throws IOException {
    try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
      byte[] data = new byte[8192];
      int bytesRead;
      
      while ((bytesRead = is.read(data, 0, data.length)) != -1) {
        buffer.write(data, 0, bytesRead);
      }
      
      return buffer.toByteArray();
    }
  }

  /**
   * Билдер для конфигурации и создания RestClient.
   * Предоставляет fluent interface для настройки всех параметров клиента.
   */
  public static class Builder {

    private String baseUrl;
    private final List<RequestInterceptor> requestInterceptors = new ArrayList<>();
    private final List<ResponseInterceptor> responseInterceptors = new ArrayList<>();
    private SSLContext sslContext;
    private boolean ignoreSSL = false;
    private int connectTimeout = 10000;
    private int readTimeout = 30000;
    private int writeTimeout = 30000;
    private int maxRetries = 0;
    private boolean followRedirects = true;
    private java.net.Proxy proxy = null;
    private RateLimiter rateLimiter = null;
    private Converter converter = null;

    /**
     * Устанавливает базовый URL для всех запросов.
     *
     * @param baseUrl базовый URL
     * @return this билдер
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Добавляет интерцептор запросов.
     *
     * @param interceptor интерцептор запросов
     * @return this билдер
     */
    public Builder addRequestInterceptor(RequestInterceptor interceptor) {
      this.requestInterceptors.add(interceptor);
      return this;
    }

    /**
     * Добавляет интерцептор ответов.
     *
     * @param interceptor интерцептор ответов
     * @return this билдер
     */
    public Builder addResponseInterceptor(ResponseInterceptor interceptor) {
      this.responseInterceptors.add(interceptor);
      return this;
    }

    /**
     * Устанавливает кастомный SSL контекст.
     *
     * @param sslContext SSL контекст
     * @return this билдер
     */
    public Builder sslContext(SSLContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    /**
     * Включает или выключает игнорирование SSL ошибок.
     *
     * @param ignoreSSL true для игнорирования SSL ошибок
     * @return this билдер
     */
    public Builder ignoreSSL(boolean ignoreSSL) {
      this.ignoreSSL = ignoreSSL;
      return this;
    }

    /**
     * Включает игнорирование SSL ошибок с созданием trust-all контекста.
     * Создает SSL контекст, который доверяет всем сертификатам.
     *
     * @return this билдер
     * @throws RuntimeException если не удается создать SSL контекст
     */
    public Builder ignoreSSL() {
      try {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return null;
              }

              @Override
              public void checkClientTrusted(X509Certificate[] certs, String authType) {
              }

              @Override
              public void checkServerTrusted(X509Certificate[] certs, String authType) {
              }
            }
        };

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new SecureRandom());
        this.sslContext = sc;
        this.ignoreSSL = true;
      } catch (NoSuchAlgorithmException | KeyManagementException e) {
        throw new RuntimeException("Failed to create SSL context", e);
      }
      return this;
    }

    /**
     * Устанавливает таймаут соединения в миллисекундах.
     *
     * @param timeoutMillis таймаут соединения
     * @return this билдер
     */
    public Builder connectTimeout(int timeoutMillis) {
      this.connectTimeout = timeoutMillis;
      return this;
    }

    /**
     * Устанавливает таймаут чтения в миллисекундах.
     *
     * @param timeoutMillis таймаут чтения
     * @return this билдер
     */
    public Builder readTimeout(int timeoutMillis) {
      this.readTimeout = timeoutMillis;
      return this;
    }

    /**
     * Устанавливает таймаут записи в миллисекундах.
     *
     * @param timeoutMillis таймаут записи
     * @return this билдер
     */
    public Builder writeTimeout(int timeoutMillis) {
      this.writeTimeout = timeoutMillis;
      return this;
    }

    /**
     * Устанавливает максимальное количество повторных попыток при ошибках.
     *
     * @param maxRetries количество повторных попыток
     * @return this билдер
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Включает или выключает автоматическое следование редиректам.
     *
     * @param followRedirects true для автоматического следования редиректам
     * @return this билдер
     */
    public Builder followRedirects(boolean followRedirects) {
      this.followRedirects = followRedirects;
      return this;
    }

    /**
     * Устанавливает HTTP прокси по хосту и порту.
     *
     * @param host хост прокси
     * @param port порт прокси
     * @return this билдер
     */
    public Builder proxy(String host, int port) {
      this.proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(host, port));
      return this;
    }

    /**
     * Устанавливает кастомный прокси объект.
     *
     * @param proxy прокси объект
     * @return this билдер
     */
    public Builder proxy(java.net.Proxy proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Устанавливает ограничитель скорости с указанным лимитом запросов в минуту.
     *
     * @param maxRequestsPerMinute максимальное количество запросов в минуту
     * @return this билдер
     */
    public Builder rateLimiter(int maxRequestsPerMinute) {
      this.rateLimiter = new SimpleRateLimiter(maxRequestsPerMinute);
      return this;
    }

    /**
     * Устанавливает кастомный ограничитель скорости.
     *
     * @param rateLimiter ограничитель скорости
     * @return this билдер
     */
    public Builder rateLimiter(RateLimiter rateLimiter) {
      this.rateLimiter = rateLimiter;
      return this;
    }

    /**
     * Устанавливает кастомный конвертер для сериализации/десериализации.
     *
     * @param converter конвертер
     * @return this билдер
     */
    public Builder converter(Converter converter) {
      this.converter = converter;
      return this;
    }

    /**
     * Создает экземпляр RestClient с текущими настройками.
     *
     * @return настроенный экземпляр RestClient
     */
    public RestClient build() {
      return new RestClient(this);
    }
  }

  /**
   * Интерфейс ограничителя скорости запросов.
   * Позволяет контролировать частоту выполнения запросов.
   */
  public interface RateLimiter {

    /**
     * Получает разрешение на выполнение запроса.
     * Может блокировать выполнение до освобождения слота.
     *
     * @throws InterruptedException если поток был прерван во время ожидания
     */
    void acquire() throws InterruptedException;
  }
}
