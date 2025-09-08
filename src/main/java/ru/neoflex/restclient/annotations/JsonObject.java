package ru.neoflex.restclient.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для указания кастомного имени корневого объекта при сериализации в JSON.
 * Применяется к классам.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonObject {

  /**
   * Кастомное имя корневого объекта в JSON.
   * Если не указано, используется имя класса.
   *
   * @return строковое значения имени корневого объекта
   */
  String value() default "";
}
