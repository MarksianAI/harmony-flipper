package com.harmony.flipper.data;

public final class Price {

    private Price() {
    }

    public static final class Latest {
        private Integer high;
        private Long highTime;
        private Integer low;
        private Long lowTime;

        public Integer getHigh() {
            return high;
        }

        public Long getHighTime() {
            return highTime;
        }

        public Integer getLow() {
            return low;
        }

        public Long getLowTime() {
            return lowTime;
        }
    }

    public static final class Aggregate {
        private Integer avgHighPrice;
        private Long highPriceVolume;
        private Integer avgLowPrice;
        private Long lowPriceVolume;

        public Integer getAvgHighPrice() {
            return avgHighPrice;
        }

        public Long getHighPriceVolume() {
            return highPriceVolume;
        }

        public Integer getAvgLowPrice() {
            return avgLowPrice;
        }

        public Long getLowPriceVolume() {
            return lowPriceVolume;
        }
    }
}
