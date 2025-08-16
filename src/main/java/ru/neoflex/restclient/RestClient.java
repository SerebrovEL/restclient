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

    public interface Converter {

        String contentType(Object body);

        byte[] write(Object body) throws IOException;

        <T> T read(byte[] data, Type type) throws IOException;
    }

    private static class JsonConverterAdapter implements Converter {

        @Override
        public String contentType(Object body) {
            return "application/json";
        }

        @Override
        public byte[] write(Object body) throws IOException {
            return JsonConverter.toJson(body).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T read(byte[] data, Type type) throws IOException {
            return JsonConverter.fromJson(new String(data, StandardCharsets.UTF_8), type);
        }
    }

    public static class SimpleRateLimiter implements RateLimiter {

        private final int maxRequestsPerMinute;
        private final Object lock = new Object();
        private long lastResetTime;
        private int requestCount;

        public SimpleRateLimiter(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
            this.lastResetTime = System.currentTimeMillis();
            this.requestCount = 0;
        }

        @Override
        public void acquire() throws InterruptedException {
            synchronized (lock) {
                if (System.currentTimeMillis() - lastResetTime > 60000) {
                    requestCount = 0;
                    lastResetTime = System.currentTimeMillis();
                }

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

    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> service) {
        return (T) serviceCache.computeIfAbsent(service, this::createService);
    }

    private <T> Object createService(Class<T> service) {
        return Proxy.newProxyInstance(service.getClassLoader(),
                new Class<?>[]{service}, (Object proxy_, Method method, Object[] args) -> processMethod(method, args));
    }

    private Object processMethod(Method method, Object[] args) throws Exception {
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

        String baseUrl_ = this.baseUrl;
        if (method.getDeclaringClass().isAnnotationPresent(BaseUrl.class)) {
            baseUrl_ = method.getDeclaringClass().getAnnotation(BaseUrl.class).value();
        }
        if (baseUrl_ == null || baseUrl_.isEmpty()) {
            throw new RuntimeException("Base URL is not specified");
        }

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

        if (!headers.containsKey("Content-Type")) {
            headers.put("Content-Type", contentType);
        }

        if (method.isAnnotationPresent(Headers.class)) {
            String[] headerValues = method.getAnnotation(Headers.class).value();
            for (String header : headerValues) {
                String[] parts = header.split(":", 2);
                if (parts.length == 2) {
                    headers.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        String urlStr = joinUrls(baseUrl_, path);
        if (!queryParams.isEmpty()) {
            urlStr += "?" + buildQueryString(queryParams);
        }

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
                    Thread.sleep(100 * (long) Math.pow(2, attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Request interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Request failed after " + maxRetries + " retries", lastException);
    }

    /**
     * Joins base URL and path, handling double slashes between them
     */
    private String joinUrls(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return path;
        }
        if (path == null || path.isEmpty()) {
            return baseUrl;
        }

        // Remove trailing slashes from baseUrl
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Remove leading slashes from path
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        return baseUrl + "/" + path;
    }

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

    private Object executeRequest(String method, String urlStr, Map<String, String> headers, Object body, Type returnType) throws Exception {
        if (rateLimiter != null) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Request interrupted by rate limiter", e);
            }
        }

        int redirectCount = 0;
        final int MAX_REDIRECTS = 5;
        HttpURLConnection connection = null;
        Exception lastException = null;

        while (redirectCount < MAX_REDIRECTS) {
            try {
                URL url = new URL(urlStr);
                if (proxy != null) {
                    connection = (HttpURLConnection) url.openConnection(proxy);
                } else {
                    connection = (HttpURLConnection) url.openConnection();
                }

                if ("https".equalsIgnoreCase(url.getProtocol()) && connection instanceof HttpsURLConnection) {
                    HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
                    if (ignoreSSL && sslContext != null) {
                        httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                        httpsConnection.setHostnameVerifier((hostname, session) -> true);
                    }
                }

                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                if (body instanceof byte[]) {
                    connection.setFixedLengthStreamingMode(((byte[]) body).length);
                }
                connection.setInstanceFollowRedirects(followRedirects);
                connection.setRequestMethod(method);

                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }

                for (RequestInterceptor interceptor : requestInterceptors) {
                    interceptor.intercept(connection);
                }

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

                int responseCode = connection.getResponseCode();

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

                    for (ResponseInterceptor interceptor : responseInterceptors) {
                        response = interceptor.intercept(responseCode, response);
                    }

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
                        // Ignore and use default
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

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

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder addRequestInterceptor(RequestInterceptor interceptor) {
            this.requestInterceptors.add(interceptor);
            return this;
        }

        public Builder addResponseInterceptor(ResponseInterceptor interceptor) {
            this.responseInterceptors.add(interceptor);
            return this;
        }

        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public Builder ignoreSSL(boolean ignoreSSL) {
            this.ignoreSSL = ignoreSSL;
            return this;
        }

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

        public Builder connectTimeout(int timeoutMillis) {
            this.connectTimeout = timeoutMillis;
            return this;
        }

        public Builder readTimeout(int timeoutMillis) {
            this.readTimeout = timeoutMillis;
            return this;
        }

        public Builder writeTimeout(int timeoutMillis) {
            this.writeTimeout = timeoutMillis;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Builder proxy(String host, int port) {
            this.proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(host, port));
            return this;
        }

        public Builder proxy(java.net.Proxy proxy) {
            this.proxy = proxy;
            return this;
        }

        public Builder rateLimiter(int maxRequestsPerMinute) {
            this.rateLimiter = new SimpleRateLimiter(maxRequestsPerMinute);
            return this;
        }

        public Builder rateLimiter(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        public Builder converter(Converter converter) {
            this.converter = converter;
            return this;
        }

        public RestClient build() {
            return new RestClient(this);
        }
    }

    public interface RateLimiter {

        void acquire() throws InterruptedException;
    }
}
