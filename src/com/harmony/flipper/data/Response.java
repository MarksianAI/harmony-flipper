package com.harmony.flipper.data;

import com.harmony.flipper.net.OsrsWikiClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Response {

    private Response() {
    }

    public static final class Latest {
        private final Map<Integer, Price.Latest> data;

        private Latest(Map<Integer, Price.Latest> data) {
            this.data = data;
        }

        public Map<Integer, Price.Latest> getData() {
            return data;
        }

        public static Latest fromRaw(Map<String, Price.Latest> raw) {
            Map<Integer, Price.Latest> converted = convertPriceMap(raw);
            return new Latest(converted);
        }
    }

    public static final class Aggregate {
        private final long timestamp;
        private final Map<Integer, Price.Aggregate> data;

        private Aggregate(long timestamp, Map<Integer, Price.Aggregate> data) {
            this.timestamp = timestamp;
            this.data = data;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Map<Integer, Price.Aggregate> getData() {
            return data;
        }

        public static Aggregate fromRaw(long timestamp, Map<String, Price.Aggregate> raw) {
            Map<Integer, Price.Aggregate> converted = convertAggregateMap(raw);
            return new Aggregate(timestamp, converted);
        }
    }

    public static final class Volume {
        private final long timestamp;
        private final Map<Integer, Long> data;

        private Volume(long timestamp, Map<Integer, Long> data) {
            this.timestamp = timestamp;
            this.data = data;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Map<Integer, Long> getData() {
            return data;
        }

        public static Volume fromRaw(long timestamp, Map<String, Long> raw) {
            Map<Integer, Long> converted = convertVolumeMap(raw);
            return new Volume(timestamp, converted);
        }
    }

    public static final class Timeseries {
        private final int itemId;
        private final List<Time.Series> data;

        private Timeseries(int itemId, List<Time.Series> data) {
            this.itemId = itemId;
            this.data = data;
        }

        public int getItemId() {
            return itemId;
        }

        public List<Time.Series> getData() {
            return data;
        }

        public static Timeseries fromRaw(int itemId, List<Time.Series> raw) {
            if (raw == null || raw.isEmpty()) {
                return new Timeseries(itemId, Collections.emptyList());
            }
            return new Timeseries(itemId, Collections.unmodifiableList(new ArrayList<>(raw)));
        }
    }

    private static Map<Integer, Price.Latest> convertPriceMap(Map<String, Price.Latest> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, Price.Latest> converted = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Price.Latest> entry : raw.entrySet()) {
            converted.put(parseItemId(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(converted);
    }

    private static Map<Integer, Price.Aggregate> convertAggregateMap(Map<String, Price.Aggregate> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, Price.Aggregate> converted = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Price.Aggregate> entry : raw.entrySet()) {
            converted.put(parseItemId(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(converted);
    }

    private static Map<Integer, Long> convertVolumeMap(Map<String, Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, Long> converted = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Long> entry : raw.entrySet()) {
            converted.put(parseItemId(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(converted);
    }

    private static int parseItemId(String rawId) {
        try {
            return Integer.parseInt(rawId);
        } catch (NumberFormatException ex) {
            throw new OsrsWikiClientException("Invalid item id: " + rawId, ex);
        }
    }
}
