package ru.neoflex.restclient.interceptors;

import java.net.HttpURLConnection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Интерцептор для логирования HTTP запросов и ответов.
 * Реализует оба интерфейса - RequestInterceptor и ResponseInterceptor.
 */
public class LoggingInterceptor implements RequestInterceptor, ResponseInterceptor {

  private static final Logger logger = Logger.getLogger(LoggingInterceptor.class.getName());

  /**
   * Логирует информацию о HTTP запросе.
   *
   * @param connection HTTP соединение для логирования
   */
  @Override
  public void intercept(HttpURLConnection connection) {
    logger.log(Level.INFO, "Request: {0} {1}", new Object[]{
        connection.getRequestMethod(),
        connection.getURL()
    });
  }

  /**
   * Логирует информацию о HTTP ответе.
   *
   * @param responseCode HTTP код ответа
   * @param response тело ответа
   * @return исходное тело ответа (не изменяется)
   */
  @Override
  public String intercept(int responseCode, String response) {
    logger.log(Level.INFO, "Response: {0} - {1}", new Object[]{
        responseCode,
        response
    });
    return response;
  }
}
