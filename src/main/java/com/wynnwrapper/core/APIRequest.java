package com.wynnwrapper.core;

import com.wynnwrapper.exceptions.WynnRateLimitException;
import com.wynnwrapper.exceptions.WynnResponseException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class APIRequest {

    private final String version;
    private final String requestURL;
    private final List<Header> headers;
    private final int timeout;
    private final APIHelper apiHelper;

    public APIRequest(APIHelper apiHelper, String requestURL) {
        this.apiHelper = apiHelper;
        this.version = apiHelper.apiVersion();
        this.timeout = apiHelper.timeout();
        this.requestURL = requestURL;
        this.headers = new ArrayList<>();
    }

    public <T> T getResponse(Class<T> clazz) {
        String jsonResponse = getJSONResponse();
        if (jsonResponse == null) {
            throw new WynnResponseException("No body in request response for " + requestURL, -1);
        }

        return apiHelper.gson().fromJson(jsonResponse, clazz);
    }

    public <T> T getResponse(Type type) {
        String jsonResponse = getJSONResponse();
        if (jsonResponse == null) {
            throw new WynnResponseException("No body in request response for " + requestURL, -1);
        }
        return apiHelper.gson().fromJson(jsonResponse, type);
    }

    public <T> T postResponse(Object payload, Class<T> clazz) {
        String jsonResponse = postJSONRequest(payload);
        if (jsonResponse == null) {
            throw new WynnResponseException("No body in request response for " + requestURL, -1);
        }
        return apiHelper.gson().fromJson(jsonResponse, clazz);
    }

    public <T> T postResponse(Object payload, Type type) {
        String jsonResponse = postJSONRequest(payload);
        if (jsonResponse == null) {
            throw new WynnResponseException("No body in request response for " + requestURL, -1);
        }
        return apiHelper.gson().fromJson(jsonResponse, type);
    }


    private String getJSONResponse() {
        if(apiHelper.rateLimiter().isRateLimited()) {
            throw new WynnRateLimitException("Cannot make request, rate limit would be exceeded. Please try again later.",
                    apiHelper.rateLimiter().rateLimitResetTimestamp(), true);
        }

        RequestConfig config = RequestConfig.custom()
                .setResponseTimeout(timeout, TimeUnit.MILLISECONDS)
                .setConnectionRequestTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();

        System.out.println("Making GET request to " + requestURL);
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            HttpGet httpGet = new HttpGet(requestURL);
            Header[] requestHeaders = new Header[headers.size()];
            httpGet.setHeaders(headers.toArray(requestHeaders));
            httpGet.setHeader("User-Agent", "WynnWrapper/" + version);

            return client.execute(httpGet, createResponseHandler());
        } catch (Exception e) {
            e.printStackTrace();
            return "";
            //throw new WynnConnectionException(e);
        }
    }

    private String postJSONRequest(Object payload) {
        if(apiHelper.rateLimiter().isRateLimited()) {
            throw new WynnRateLimitException("Cannot make request, rate limit would be exceeded. Please try again later.",
                    apiHelper.rateLimiter().rateLimitResetTimestamp(), true);
        }

        RequestConfig config = RequestConfig.custom()
                .setResponseTimeout(timeout, TimeUnit.MILLISECONDS)
                .setConnectionRequestTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();

        System.out.println("Making POST request to " + requestURL);
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            HttpPost httpPost = new HttpPost(requestURL);
            Header[] requestHeaders = new Header[headers.size()];
            httpPost.setHeaders(headers.toArray(requestHeaders));
            httpPost.setHeader("User-Agent", "WynnWrapper/" + version);
            httpPost.setHeader("Content-Type", "application/json");

            // Convert payload to JSON and add as entity
            String jsonPayload = apiHelper.gson().toJson(payload);
            httpPost.setEntity(new StringEntity(jsonPayload));

            return client.execute(httpPost, createResponseHandler());
        } catch (Exception e) {
            e.printStackTrace();
            return "";
            //throw new WynnConnectionException(e);
        }
    }

    private HttpClientResponseHandler<String> createResponseHandler() {
        return response -> {
            long rateLimitReset = Long.parseLong(response.getFirstHeader("RateLimit-Reset").getValue()) * 1000 + System.currentTimeMillis();
            int rateLimitMax = Integer.parseInt(response.getFirstHeader("RateLimit-Limit").getValue());
            int rateLimitRemaining = Integer.parseInt(response.getFirstHeader("RateLimit-Remaining").getValue());
            apiHelper.rateLimiter().updateRateLimit(rateLimitReset, rateLimitRemaining, rateLimitMax);
            int statusCode = response.getCode();

            switch (statusCode) {
                case HttpStatus.SC_OK: {
                    HttpEntity entity = response.getEntity();
                    String responseString = entity != null ? EntityUtils.toString(entity) : null;
                    if (responseString == null)
                        throw new WynnResponseException("No body in request response for " + requestURL, -1);
                    if (responseString.matches("\\{\"message\":\".*\"}")) {
                        throw new WynnResponseException("API error when requesting " + requestURL + ": " +
                                responseString.split("\"message\":")[1].replace("\"", "").replace("}", ""), -1);
                    } else if (responseString.matches("\\{\"error\":\".*\"}")) {
                        throw new WynnResponseException("API error when requesting " + requestURL + ": " +
                                responseString.split("\"error\":")[1].replace("\"", "").replace("}", ""), -1);
                    }
                    if (!entity.getContentType().contains("application/json"))
                        throw new WynnResponseException("Unexpected content type (not application/json): " + entity.getContentType(), -1);
                    return responseString;
                }
                // Error cases remain the same as in getJSONResponse
                case HttpStatus.SC_BAD_REQUEST:
                    throw new WynnResponseException("400: Bad Request for " + requestURL, 400);
                case 429:
                    long resetTime;
                    try {
                        resetTime = Long.parseLong(response.getFirstHeader("ratelimit-reset").getValue()) * 1000 + System.currentTimeMillis();
                    } catch (NumberFormatException ex) {
                        resetTime = -1;
                    }
                    throw new WynnRateLimitException("429: Too Many Requests for " + requestURL, resetTime, true);
                case HttpStatus.SC_NOT_FOUND:
                    throw new WynnResponseException("404: Not Found for " + requestURL, 404);
                case HttpStatus.SC_SERVICE_UNAVAILABLE:
                    throw new WynnResponseException("503: Service Unavailable " + requestURL, 503);
                default:
                    throw new WynnResponseException("Unexpected status code " + statusCode + " returned by API for request " + requestURL, statusCode);
            }
        };
    }
}
