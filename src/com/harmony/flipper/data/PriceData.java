package com.harmony.flipper.data;

public class PriceData {
    public final int itemId;
    public final int high;
    public final int low;
    public final long highTime;
    public final long lowTime;

    public PriceData(int itemId, int high, int low, long highTime, long lowTime) {
        this.itemId = itemId;
        this.high = high;
        this.low = low;
        this.highTime = highTime;
        this.lowTime = lowTime;
    }
}
