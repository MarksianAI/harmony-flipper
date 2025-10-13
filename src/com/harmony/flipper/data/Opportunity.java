package com.harmony.flipper.data;

public class Opportunity {
    public final int itemId;
    public final String itemName;
    public final int buyPrice;
    public final int sellPrice;
    public final int profit;    // sell - buy
    public final double roi;    // %
    public final int volume;    // recent volume from API
    public final Integer limit; // GE buy limit (nullable)

    public Opportunity(int itemId, String itemName, int buyPrice, int sellPrice,
                       int profit, double roi, int volume, Integer limit) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.profit = profit;
        this.roi = roi;
        this.volume = volume;
        this.limit = limit;
    }

    /**
     * naive estimate: profit * min(volume, 6*limit) if limit known
     */
    public long estimatedDailyGp() {
        long vol = volume;
        if (limit != null && limit > 0) {
            vol = Math.min(vol, (long) limit * 6L);
        }
        return (long) profit * Math.max(1L, vol);
    }
}
