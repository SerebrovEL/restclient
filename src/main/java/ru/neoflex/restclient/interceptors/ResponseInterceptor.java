package ru.neoflex.restclient.interceptors;

/**
 * Интерфейс для перехватчиков HTTP ответов.
 * Позволяет обрабатывать ответ перед возвратом клиенту.
 */
public interface ResponseInterceptor {

  /**
   * Перехватывает и потенциально модифицирует HTTP ответ.
   *
   * @param responseCode HTTP код ответа
   * @param response тело ответа
   * @return обработанное тело ответа
   */
  String intercept(int responseCode, String response);
}
