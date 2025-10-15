package com.harmony.flipper.data;

public final class Timeseries {

    private Timeseries() {
    }

    public static final class Point {
        private final long timestamp;
        private final int avgHighPrice;
        private final int avgLowPrice;
        private final int highPriceVolume;
        private final int lowPriceVolume;

        public Point(long timestamp,
                     int avgHighPrice,
                     int avgLowPrice,
                     int highPriceVolume,
                     int lowPriceVolume) {
            this.timestamp = timestamp;
            this.avgHighPrice = avgHighPrice;
            this.avgLowPrice = avgLowPrice;
            this.highPriceVolume = Math.max(0, highPriceVolume);
            this.lowPriceVolume = Math.max(0, lowPriceVolume);
        }

        public long timestamp() {
            return timestamp;
        }

        public int avgHighPrice() {
            return avgHighPrice;
        }

        public int avgLowPrice() {
            return avgLowPrice;
        }

        public int highPriceVolume() {
            return highPriceVolume;
        }

        public int lowPriceVolume() {
            return lowPriceVolume;
        }
    }
}
