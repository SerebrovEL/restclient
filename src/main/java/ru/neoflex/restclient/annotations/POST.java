package ru.neoflex.restclient.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для обозначения HTTP POST запроса.
 * Применяется к методам REST-сервиса.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface POST {

  /**
   * Путь относительно базового URL.
   *
   * @return строковое значение пути
   */
  String value() default "";
}
