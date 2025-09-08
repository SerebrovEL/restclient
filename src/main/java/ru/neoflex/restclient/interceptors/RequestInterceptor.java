package ru.neoflex.restclient.interceptors;

import java.net.HttpURLConnection;

/**
 * Интерфейс для перехватчиков HTTP запросов.
 * Позволяет модифицировать запрос перед отправкой.
 */
public interface RequestInterceptor {

  /**
   * Перехватывает и потенциально модифицирует HTTP запрос.
   *
   * @param connection HTTP соединение для модификации
   */
  void intercept(HttpURLConnection connection);
}
