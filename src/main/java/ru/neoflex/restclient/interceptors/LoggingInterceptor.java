package ru.neoflex.restclient.interceptors;

import java.net.HttpURLConnection;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoggingInterceptor implements RequestInterceptor, ResponseInterceptor {

    private static final Logger logger = Logger.getLogger(LoggingInterceptor.class.getName());

    @Override
    public void intercept(HttpURLConnection connection) {
        logger.log(Level.INFO, "Request: {0} {1}", new Object[]{
            connection.getRequestMethod(),
            connection.getURL()
        });
    }

    @Override
    public String intercept(int responseCode, String response) {
        logger.log(Level.INFO, "Response: {0} - {1}", new Object[]{
            responseCode,
            response
        });
        return response;
    }
}
