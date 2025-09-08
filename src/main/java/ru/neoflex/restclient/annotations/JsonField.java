package ru.neoflex.restclient.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для кастомного именования полей при сериализации в JSON.
 * Применяется к полям классов.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface JsonField {

  /**
   * Кастомное имя поля в JSON.
   * Если не указано, используется имя поля класса.
   *
   * @return строковое значение имени поля в JSON
   */
  String value() default "";
}
