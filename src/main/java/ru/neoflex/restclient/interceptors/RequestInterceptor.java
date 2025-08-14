package ru.neoflex.restclient.interceptors;

import java.net.HttpURLConnection;

public interface RequestInterceptor {

    void intercept(HttpURLConnection connection);
}
