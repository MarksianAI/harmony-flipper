package com.harmony.flipper.net.transport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.harmony.flipper.data.ItemMapping;
import com.harmony.flipper.data.Price;
import com.harmony.flipper.data.Time;
import com.harmony.flipper.net.OsrsWikiClientException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OsrsWikiTransport {

    private static final URI DEFAULT_BASE_URI = URI.create("https://prices.runescape.wiki/api/v1/osrs/");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Type RAW_LATEST_TYPE = new TypeToken<RawLatestResponse>() {
    }.getType();
    private static final Type RAW_AGGREGATE_TYPE = new TypeToken<RawAggregateResponse>() {
    }.getType();
    private static final Type RAW_VOLUME_TYPE = new TypeToken<RawVolumeResponse>() {
    }.getType();
    private static final Type RAW_TIMESERIES_TYPE = new TypeToken<RawTimeseriesResponse>() {
    }.getType();
    private static final Type MAPPING_LIST_TYPE = new TypeToken<List<ItemMapping>>() {
    }.getType();

    private final HttpClient httpClient;
    private final Gson gson;
    private final URI baseUri;
    private final String userAgent;
    private final Duration requestTimeout;

    public OsrsWikiTransport(String userAgent) {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                new GsonBuilder().serializeNulls().create(),
                DEFAULT_BASE_URI,
                userAgent,
                DEFAULT_TIMEOUT);
    }

    public OsrsWikiTransport(HttpClient httpClient, Gson gson, URI baseUri, String userAgent, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.userAgent = normalizeUserAgent(userAgent);
        Duration timeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = timeout;
    }

    public RawLatestResponse fetchLatest() {
        return get("latest", Collections.emptyMap(), RAW_LATEST_TYPE);
    }

    public RawAggregateResponse fetchAggregate(String bucketPath) {
        return get(bucketPath, Collections.emptyMap(), RAW_AGGREGATE_TYPE);
    }

    public RawVolumeResponse fetchVolumes() {
        return get("volumes", Collections.emptyMap(), RAW_VOLUME_TYPE);
    }

    public RawTimeseriesResponse fetchTimeseries(Map<String, String> params) {
        return get("timeseries", params, RAW_TIMESERIES_TYPE);
    }

    public List<ItemMapping> fetchMapping() {
        List<ItemMapping> mapping = get("mapping", Collections.emptyMap(), MAPPING_LIST_TYPE);
        if (mapping == null || mapping.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(mapping);
    }

    private <T> T get(String path, Map<String, String> queryParams, Type type) {
        URI uri = buildUri(path, queryParams);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OsrsWikiClientException("Request interrupted for " + uri, e);
        } catch (IOException e) {
            throw new OsrsWikiClientException("Failed to call " + uri, e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = response.body();
            String snippet = body == null ? "" : body.substring(0, Math.min(256, body.length()));
            throw new OsrsWikiClientException("Unexpected response " + status + " from " + uri + ": " + snippet);
        }

        try {
            return gson.fromJson(response.body(), type);
        } catch (JsonParseException e) {
            throw new OsrsWikiClientException("Unable to parse response from " + uri, e);
        }
    }

    private URI buildUri(String path, Map<String, String> queryParams) {
        StringBuilder builder = new StringBuilder(path);
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            builder.append(first ? '?' : '&');
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return baseUri.resolve(builder.toString());
    }

    private String normalizeUserAgent(String userAgent) {
        if (userAgent == null) {
            throw new IllegalArgumentException("userAgent cannot be null");
        }
        String trimmed = userAgent.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("userAgent cannot be blank");
        }
        return trimmed;
    }

    public static final class RawLatestResponse {
        public Map<String, Price.Latest> data;
    }

    public static final class RawAggregateResponse {
        public long timestamp;
        public Map<String, Price.Aggregate> data;
    }

    public static final class RawVolumeResponse {
        public long timestamp;
        public Map<String, Long> data;
    }

    public static final class RawTimeseriesResponse {
        public int itemId;
        public List<Time.Series> data;
    }
}
