package com.harmony.flipper.data;

import com.google.gson.annotations.SerializedName;

public final class ItemMapping {
    private String examine;
    private int id;
    private boolean members;
    @SerializedName("lowalch")
    private Integer lowAlch;
    private Integer limit;
    private Integer value;
    @SerializedName("highalch")
    private Integer highAlch;
    private String icon;
    private String name;

    public String getExamine() {
        return examine;
    }

    public int getId() {
        return id;
    }

    public boolean isMembers() {
        return members;
    }

    public Integer getLowAlch() {
        return lowAlch;
    }

    public Integer getLimit() {
        return limit;
    }

    public Integer getValue() {
        return value;
    }

    public Integer getHighAlch() {
        return highAlch;
    }

    public String getIcon() {
        return icon;
    }

    public String getName() {
        return name;
    }
}
