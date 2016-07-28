package com.bayapps.android.robophish.model;

/**
 * Created by mvoytovich on 1/13/15.
 */
import com.loopj.android.http.*;

public class HttpClient {
    private static final String BASE_URL = "http://phish.in/api/v1/";

    private static SyncHttpClient client = new SyncHttpClient();
    private static AsyncHttpClient asyncClient = new AsyncHttpClient();

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.get(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void getAsync(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        asyncClient.get(getAbsoluteUrl(url), params, responseHandler);
    }

    private static String getAbsoluteUrl(String relativeUrl) {
        return BASE_URL + relativeUrl;
    }
}