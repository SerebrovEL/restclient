package ru.neoflex.restclient.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для указания базового URL для REST-сервиса.
 * Применяется на уровне класса интерфейса сервиса.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BaseUrl {

  /**
   * Базовый URL для всех запросов сервиса.
   *
   * @return строковое значение базового URL
   */
  String value();
}
