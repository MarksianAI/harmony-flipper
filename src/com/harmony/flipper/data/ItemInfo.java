package com.harmony.flipper.data;

public class ItemInfo {
    public final int id;
    public final String name;
    public final String examine;
    public final boolean members;
    public final int lowAlch;
    public final int highAlch;
    public final Integer limit; // GE buy limit (nullable)

    public ItemInfo(int id, String name, String examine, boolean members, int lowAlch, int highAlch, Integer limit) {
        this.id = id;
        this.name = name;
        this.examine = examine;
        this.members = members;
        this.lowAlch = lowAlch;
        this.highAlch = highAlch;
        this.limit = limit;
    }
}
