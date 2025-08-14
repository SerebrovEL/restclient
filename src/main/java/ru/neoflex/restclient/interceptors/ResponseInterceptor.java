package ru.neoflex.restclient.interceptors;

public interface ResponseInterceptor {

    String intercept(int responseCode, String response);
}
