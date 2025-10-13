package com.harmony.flipper.data;

public class Flip {
    public final int itemId;
    public final String itemName;
    public final int buyPrice;
    public final int sellPrice;
    public final int quantity;

    public Flip(int itemId, String itemName, int buyPrice, int sellPrice, int quantity) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.quantity = quantity;
    }
}
