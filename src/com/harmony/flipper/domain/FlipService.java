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
import org.rspeer.game.Game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int REFRESH_LATEST_TICKS       = 100;   // re-pull /latest at most once per minute
    private static final int REFRESH_VOLUMES_TICKS      = 500;   // re-pull /volumes every 5 min
    private static final int REFRESH_INTERVAL_24H_TICKS = 500;   // re-pull /24h every 5 min (sliding window)
    private static final int REFRESH_INTERVAL_1H_TICKS  = 500;   // re-pull /1h every 5 min

    private final Config config;
    private final String userAgent;

    private final Map<Integer, ItemInfo> itemMapping   = new ConcurrentHashMap<>();
    private final Map<Integer, PriceData> latestPrices = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> itemVolumes    = new ConcurrentHashMap<>();

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

    private final Map<Integer, IntervalData> prices24h = new ConcurrentHashMap<>();
    private final Map<Integer, IntervalData> prices1h  = new ConcurrentHashMap<>();

    private volatile boolean mappingLoaded = false;
    private int tickCounter = 0;

    private volatile long lastLatestFetchTick  = Long.MIN_VALUE;
    private volatile long lastVolumesFetchTick = Long.MIN_VALUE;
    private volatile long last24hFetchTick     = Long.MIN_VALUE;
    private volatile long last1hFetchTick      = Long.MIN_VALUE;

    @Inject
    public FlipService(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.userAgent = sanitizeUA(config.advanced.userAgent);
    }

    @Subscribe
    public void onTick(TickEvent event) {
        if (!mappingLoaded) {
            try {
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

                mappingLoaded = true;
                Log.info("FlipService: loaded item mapping (" + itemMapping.size() + " items)");
                refreshSnapshot(H24_URL, data -> loadIntervals(data, prices24h), tick -> last24hFetchTick = tick, "24h stats");
                refreshSnapshot(H1_URL, data -> loadIntervals(data, prices1h), tick -> last1hFetchTick = tick, "1h stats");
                refreshSnapshot(VOLUMES_URL, this::loadVolumes, tick -> lastVolumesFetchTick = tick, "volumes");
                refreshSnapshot(LATEST_URL, this::loadLatest, tick -> lastLatestFetchTick = tick, "latest prices");
            } catch (IOException | JSONException ex) {
                Log.severe("FlipService: failed to fetch item mapping: " + ex.getMessage());
            }
            return;
        }

        int throttle = Math.max(1, config.advanced.tickThrottle);
        if (tickCounter < throttle) {
            tickCounter++;
            return;
        }
        tickCounter = 0;

        if (shouldRefresh(lastLatestFetchTick, REFRESH_LATEST_TICKS)) {
            refreshSnapshot(LATEST_URL, this::loadLatest, tick -> lastLatestFetchTick = tick, "latest prices");
        }
        if (shouldRefresh(lastVolumesFetchTick, REFRESH_VOLUMES_TICKS)) {
            refreshSnapshot(VOLUMES_URL, this::loadVolumes, tick -> lastVolumesFetchTick = tick, "volumes");
        }
        if (shouldRefresh(last24hFetchTick, REFRESH_INTERVAL_24H_TICKS)) {
            refreshSnapshot(H24_URL, data -> loadIntervals(data, prices24h), tick -> last24hFetchTick = tick, "24h stats");
        }
        if (shouldRefresh(last1hFetchTick, REFRESH_INTERVAL_1H_TICKS)) {
            refreshSnapshot(H1_URL, data -> loadIntervals(data, prices1h), tick -> last1hFetchTick = tick, "1h stats");
        }
    }

    /**
     * @return true once mapping has loaded and price/volume + 24h stats are populated.
     */
    public boolean isReady() {
        return mappingLoaded
                && !latestPrices.isEmpty()
                && !itemVolumes.isEmpty()
                && !prices24h.isEmpty();
    }

    /** @return immutable view of the item ids known to the service. */
    public Set<Integer> getItemIds() {
        return Set.copyOf(itemMapping.keySet());
    }

    /** @return immutable view of the item mapping. */
    public Map<Integer, ItemInfo> getItemMapping() {
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
        ItemInfo info = itemMapping.get(itemId);
        PriceData price = latestPrices.get(itemId);
        Integer volume = itemVolumes.get(itemId);
        if (info == null || price == null || volume == null) {
            return null;
        }
        return new MarketRow(info, price, volume);
    }

    public long getLastLatestFetchTick()  { return lastLatestFetchTick; }
    public long getLastVolumesFetchTick() { return lastVolumesFetchTick; }
    public long getLast24hFetchTick()     { return last24hFetchTick; }
    public long getLast1hFetchTick()      { return last1hFetchTick; }


    // ---------- internals ----------

    private boolean shouldRefresh(long lastTick, int minIntervalTicks) {
        if (lastTick == Long.MIN_VALUE) {
            return true;
        }

        return (Game.getTickCount() - lastTick) >= minIntervalTicks;
    }

    private void loadLatest(JSONObject data) throws JSONException {
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
    }

    private void loadVolumes(JSONObject data) throws JSONException {
        itemVolumes.clear();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String idKey = keys.next();
            int id = Integer.parseInt(idKey);
            int volume = data.optInt(idKey, 0);
            itemVolumes.put(id, volume);
        }
    }

    private void loadIntervals(JSONObject data, Map<Integer, IntervalData> target) throws JSONException {
        target.clear();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String idKey = keys.next();
            JSONObject obj = data.getJSONObject(idKey);
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

    private void refreshSnapshot(String url,
                                 SnapshotLoader loader,
                                 java.util.function.LongConsumer tickSetter,
                                 String label) {
        try {
            JSONObject root = new JSONObject(httpGet(url));
            loader.load(root.getJSONObject("data"));
            tickSetter.accept(Game.getTickCount());
        } catch (IOException | JSONException ex) {
            Log.severe("FlipService: failed to fetch " + label + ": " + ex.getMessage());
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

    @FunctionalInterface
    private interface SnapshotLoader {
        void load(JSONObject data) throws JSONException;
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
