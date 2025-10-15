package com.harmony.flipper.data;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class Market {

    private Market() {
    }

    public static final class Snapshot {
        private final Map<Integer, JSONObject> itemMapping;
        private final Map<Integer, JSONObject> latestPrices;
        private final Map<Integer, Integer> itemVolumes;

        public Snapshot(Map<Integer, JSONObject> itemMapping,
                        Map<Integer, JSONObject> latestPrices,
                        Map<Integer, Integer> itemVolumes) {
            this.itemMapping = unmodifiableCopy(itemMapping, "itemMapping");
            this.latestPrices = unmodifiableCopy(latestPrices, "latestPrices");
            this.itemVolumes = unmodifiableCopy(itemVolumes, "itemVolumes");
        }

        public Map<Integer, JSONObject> itemMapping() {
            return itemMapping;
        }

        public Map<Integer, JSONObject> latestPrices() {
            return latestPrices;
        }

        public Map<Integer, Integer> itemVolumes() {
            return itemVolumes;
        }

        private static <T> Map<Integer, T> unmodifiableCopy(Map<Integer, T> source, String label) {
            Objects.requireNonNull(source, label);
            return Collections.unmodifiableMap(new HashMap<>(source));
        }
    }
}
