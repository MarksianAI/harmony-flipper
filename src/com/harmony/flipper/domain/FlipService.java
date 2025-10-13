package com.harmony.flipper.domain;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.harmony.flipper.config.Config;
import com.harmony.flipper.data.ItemInfo;
import com.harmony.flipper.data.PriceData;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.rspeer.commons.logging.Log;
import org.rspeer.event.Service;
import org.rspeer.event.Subscribe;
import org.rspeer.game.event.TickEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Maintains OSRS Wiki price metadata and exposes immutable snapshots for consumers.
 * Now includes interval stats (24h and 1h) for strategy services.
 */
@Singleton
public final class FlipService implements Service {

    private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final String LATEST_URL  = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String VOLUMES_URL = "https://prices.runescape.wiki/api/v1/osrs/volumes";

    // Interval endpoints (extend as needed)
    private static final String H24_URL     = "https://prices.runescape.wiki/api/v1/osrs/24h";
    private static final String H1_URL      = "https://prices.runescape.wiki/api/v1/osrs/1h";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 15_000;
    private static final int HTTP_ATTEMPTS      = 3;

    // Staleness / refresh cadences
    private static final int DEFAULT_STALE_SECONDS        = 600;   // latest high/low times
    private static final int REFRESH_LATEST_SECONDS       = 60;    // re-pull /latest at most once per minute
    private static final int REFRESH_VOLUMES_SECONDS      = 300;   // re-pull /volumes every 5 min
    private static final int REFRESH_INTERVAL_24H_SECONDS = 300;   // re-pull /24h every 5 min (sliding window)
    private static final int REFRESH_INTERVAL_1H_SECONDS  = 300;   // re-pull /1h every 5 min

    private final Config config;
    private final String userAgent;

    private final Map<Integer, ItemInfo> itemMapping   = new HashMap<>();
    private final Map<Integer, PriceData> latestPrices = new HashMap<>();
    private final Map<Integer, Integer> itemVolumes    = new HashMap<>();

    // Interval stats
    public static final class IntervalData {
        public final int avgHighPrice;
        public final int avgLowPrice;
        public final int highPriceVolume;
        public final int lowPriceVolume;

        public IntervalData(int avgHighPrice, int avgLowPrice, int highPriceVolume, int lowPriceVolume) {
            this.avgHighPrice = avgHighPrice;
            this.avgLowPrice = avgLowPrice;
            this.highPriceVolume = highPriceVolume;
            this.lowPriceVolume = lowPriceVolume;
        }
    }

    private final Map<Integer, IntervalData> prices24h = new HashMap<>();
    private final Map<Integer, IntervalData> prices1h  = new HashMap<>();

    private boolean mappingLoaded = false;
    private int tickCounter = 0;

    private long lastLatestFetchEpoch  = 0;
    private long lastVolumesFetchEpoch = 0;
    private long last24hFetchEpoch     = 0;
    private long last1hFetchEpoch      = 0;

    @Inject
    public FlipService(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.userAgent = sanitizeUA(config.advanced.userAgent);
    }

    /**
     * Eagerly loads the mapping if not already loaded.
     */
    public void preload() {
        ensureMappingLoaded();
    }

    @Subscribe
    public void onTick(TickEvent event) {
        ensureMappingLoaded();

        if (!shouldUpdateThisTick()) {
            return;
        }
        tickCounter = 0;

        // Periodic updates, lightly rate-limited
        if (shouldRefresh(lastLatestFetchEpoch, REFRESH_LATEST_SECONDS)) {
            fetchLatestWithLogging();
        }
        if (shouldRefresh(lastVolumesFetchEpoch, REFRESH_VOLUMES_SECONDS)) {
            fetchVolumesWithLogging();
        }
        if (shouldRefresh(last24hFetchEpoch, REFRESH_INTERVAL_24H_SECONDS)) {
            fetch24hWithLogging();
        }
        if (shouldRefresh(last1hFetchEpoch, REFRESH_INTERVAL_1H_SECONDS)) {
            fetch1hWithLogging();
        }
    }

    /**
     * @return true once mapping has loaded and price/volume + 24h stats are populated.
     */
    public boolean isReady() {
        ensureMappingLoaded();
        return mappingLoaded
                && !latestPrices.isEmpty()
                && !itemVolumes.isEmpty()
                && !prices24h.isEmpty(); // Require 24h for MR/Pair
    }

    /** @return immutable view of the item ids known to the service. */
    public Set<Integer> getItemIds() {
        ensureMappingLoaded();
        return Set.copyOf(itemMapping.keySet());
    }

    /** @return immutable view of the item mapping. */
    public Map<Integer, ItemInfo> getItemMapping() {
        ensureMappingLoaded();
        return Collections.unmodifiableMap(itemMapping);
    }

    /** @return immutable view of the latest price snapshot. */
    public Map<Integer, PriceData> getLatestPrices() {
        return Collections.unmodifiableMap(latestPrices);
    }

    /** @return immutable view of the latest volume snapshot (daily). */
    public Map<Integer, Integer> getItemVolumes() {
        return Collections.unmodifiableMap(itemVolumes);
    }

    /** @return immutable view of 24h interval stats. */
    public Map<Integer, IntervalData> getPrices24h() {
        return Collections.unmodifiableMap(prices24h);
    }

    /** @return immutable view of 1h interval stats. */
    public Map<Integer, IntervalData> getPrices1h() {
        return Collections.unmodifiableMap(prices1h);
    }

    /** Combines mapping, latest, volume into a row (without interval stats). */
    public MarketRow getMarketRow(int itemId) {
        ensureMappingLoaded();
        ItemInfo info = itemMapping.get(itemId);
        PriceData price = latestPrices.get(itemId);
        Integer volume = itemVolumes.get(itemId);
        if (info == null || price == null || volume == null) {
            return null;
        }
        return new MarketRow(info, price, volume);
    }

    public long getLastLatestFetchEpoch()  { return lastLatestFetchEpoch; }
    public long getLastVolumesFetchEpoch() { return lastVolumesFetchEpoch; }
    public long getLast24hFetchEpoch()     { return last24hFetchEpoch; }
    public long getLast1hFetchEpoch()      { return last1hFetchEpoch; }

    /** Determines whether the provided price snapshot is stale. */
    public boolean isStale(PriceData price) {
        Objects.requireNonNull(price, "price");
        long newest = Math.max(price.highTime, price.lowTime);
        if (newest <= 0) return true;
        return epochSeconds() - newest > DEFAULT_STALE_SECONDS;
    }

    // ---------- internals ----------

    private boolean shouldUpdateThisTick() {
        tickCounter++;
        int throttle = Math.max(1, config.advanced.tickThrottle);
        return tickCounter >= throttle;
    }

    private boolean shouldRefresh(long lastEpoch, int minIntervalSeconds) {
        long now = epochSeconds();
        return (now - lastEpoch) >= minIntervalSeconds;
    }

    private void ensureMappingLoaded() {
        if (mappingLoaded) {
            return;
        }
        try {
            loadMapping();
            mappingLoaded = true;
            Log.info("FlipService: loaded item mapping (" + itemMapping.size() + " items)");
            fetch24hWithLogging();
            fetch1hWithLogging();
            fetchVolumesWithLogging();
            fetchLatestWithLogging();
        } catch (IOException | JSONException ex) {
            Log.severe("FlipService: failed to fetch item mapping: " + ex.getMessage());
        }
    }

    private void loadMapping() throws IOException, JSONException {
        JSONArray array = new JSONArray(httpGet(MAPPING_URL));
        itemMapping.clear();

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            int id = obj.getInt("id");
            String name = obj.getString("name");
            String examine = obj.optString("examine", "");
            boolean members = obj.optBoolean("members", false);
            int lowAlch = obj.optInt("lowalch", 0);
            int highAlch = obj.optInt("highalch", 0);
            Integer limit = obj.has("limit") ? obj.getInt("limit") : null;
            itemMapping.put(id, new ItemInfo(id, name, examine, members, lowAlch, highAlch, limit));
        }
    }

    private void fetchLatestWithLogging() {
        try {
            fetchLatest();
        } catch (IOException | JSONException ex) {
            Log.severe("FlipService: failed to fetch latest prices: " + ex.getMessage());
        }
    }

    private void fetchLatest() throws IOException, JSONException {
        JSONObject root = new JSONObject(httpGet(LATEST_URL));
        JSONObject data = root.getJSONObject("data");
        latestPrices.clear();

        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String idKey = keys.next();
            JSONObject obj = data.getJSONObject(idKey);
            int high = obj.optInt("high", -1);
            int low  = obj.optInt("low", -1);
            if (high <= 0 || low <= 0) {
                continue;
            }
            int id = Integer.parseInt(idKey);
            long highTime = obj.optLong("highTime", 0);
            long lowTime  = obj.optLong("lowTime", 0);
            latestPrices.put(id, new PriceData(id, high, low, highTime, lowTime));
        }
        lastLatestFetchEpoch = epochSeconds();
    }

    private void fetchVolumesWithLogging() {
        try {
            fetchVolumes();
        } catch (IOException | JSONException ex) {
            Log.severe("FlipService: failed to fetch volumes: " + ex.getMessage());
        }
    }

    private void fetchVolumes() throws IOException, JSONException {
        JSONObject root = new JSONObject(httpGet(VOLUMES_URL));
        JSONObject data = root.getJSONObject("data");
        itemVolumes.clear();

        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String idKey = keys.next();
            int id = Integer.parseInt(idKey);
            int volume = data.optInt(idKey, 0);
            itemVolumes.put(id, volume);
        }
        lastVolumesFetchEpoch = epochSeconds();
    }

    private void fetch24hWithLogging() {
        try {
            fetchIntervalInto(H24_URL, prices24h);
            last24hFetchEpoch = epochSeconds();
        } catch (IOException | JSONException ex) {
            Log.severe("FlipService: failed to fetch 24h stats: " + ex.getMessage());
        }
    }

    private void fetch1hWithLogging() {
        try {
            fetchIntervalInto(H1_URL, prices1h);
            last1hFetchEpoch = epochSeconds();
        } catch (IOException | JSONException ex) {
            Log.severe("FlipService: failed to fetch 1h stats: " + ex.getMessage());
        }
    }

    private void fetchIntervalInto(String url, Map<Integer, IntervalData> target) throws IOException, JSONException {
        JSONObject root = new JSONObject(httpGet(url));
        JSONObject data = root.getJSONObject("data");
        target.clear();

        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String idKey = keys.next();
            JSONObject obj = data.getJSONObject(idKey);

            // Fields per item for interval endpoints
            int avgHigh = obj.optInt("avgHighPrice", -1);
            int avgLow  = obj.optInt("avgLowPrice", -1);
            int highVol = obj.optInt("highPriceVolume", 0);
            int lowVol  = obj.optInt("lowPriceVolume", 0);
            if (avgHigh < 0 || avgLow < 0) {
                continue;
            }
            int id = Integer.parseInt(idKey);
            target.put(id, new IntervalData(avgHigh, avgLow, highVol, lowVol));
        }
    }

    private String httpGet(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= HTTP_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(url);
                return readResponse(connection);
            } catch (IOException ex) {
                last = ex;
                if (attempt < HTTP_ATTEMPTS) {
                    Log.fine("FlipService: attempt " + attempt + " failed for " + url + ": " + ex.getMessage());
                }
            } finally {
                //noinspection ConstantConditions
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw last != null ? last : new IOException("HTTP GET failed: " + url);
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", userAgent);
        return connection;
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        }
    }

    private String sanitizeUA(String ua) {
        if (ua == null || ua.isBlank()) {
            return "HarmonyFlipper/1.0 (contact: please-set-config)";
        }
        return ua;
    }

    private long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    // ---------- DTOs ----------

    /** Basic row; interval stats are accessed via getPrices24h()/getPrices1h(). */
    public static final class MarketRow {
        public final ItemInfo info;
        public final PriceData price;
        public final int volume;
        public MarketRow(ItemInfo info, PriceData price, int volume) {
            this.info = Objects.requireNonNull(info, "info");
            this.price = Objects.requireNonNull(price, "price");
            this.volume = volume;
        }
    }
}
