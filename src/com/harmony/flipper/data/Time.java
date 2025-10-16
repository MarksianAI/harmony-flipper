package com.harmony.flipper.data;

public final class Time {

    private Time() {
    }

    public static final class Series {
        private long timestamp;
        private Integer avgHighPrice;
        private Integer avgLowPrice;
        private Long highPriceVolume;
        private Long lowPriceVolume;

        public long getTimestamp() {
            return timestamp;
        }

        public Integer getAvgHighPrice() {
            return avgHighPrice;
        }

        public Integer getAvgLowPrice() {
            return avgLowPrice;
        }

        public Long getHighPriceVolume() {
            return highPriceVolume;
        }

        public Long getLowPriceVolume() {
            return lowPriceVolume;
        }
    }

    public enum Step {
        FIVE_MINUTES("5m"),
        ONE_HOUR("1h"),
        SIX_HOURS("6h");

        private final String value;

        Step(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
