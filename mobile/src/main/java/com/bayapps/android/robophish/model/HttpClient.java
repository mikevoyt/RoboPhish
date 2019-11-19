package com.bayapps.android.robophish.model;

import com.bayapps.android.robophish.BuildConfig;
import com.loopj.android.http.*;

/**
 * Created by mvoytovich on 1/13/15.
 */
public class HttpClient {
    private static final String BASE_URL = "http://phish.in/api/v1/";

    private static SyncHttpClient client = new SyncHttpClient();
    private static AsyncHttpClient asyncClient = new AsyncHttpClient();

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.setTimeout(60*1000);
        client.addHeader("Authorization", String.format("Bearer %s", BuildConfig.PHISHIN_API_KEY));
        client.get(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void getAsync(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        asyncClient.get(getAbsoluteUrl(url), params, responseHandler);
    }

    private static String getAbsoluteUrl(String relativeUrl) {
        return BASE_URL + relativeUrl;
    }
}
