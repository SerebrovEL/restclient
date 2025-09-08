package ru.neoflex.restclient.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для указания параметра запроса (query parameter) в URL.
 * Применяется к параметрам методов REST-сервиса.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Query {

  /**
   * Имя параметра запроса.
   *
   * @return строковое значение имени параметра запроса
   */
  String value();
}
