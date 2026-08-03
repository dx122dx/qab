package com.billy65536.qab.planner.model;

import com.google.gson.annotations.SerializedName;

/**
 * 购物计划中的一条记录，表示在特定商店购买的物品。
 */
public class PlanEntry {
    @SerializedName("position")
    private String position;

    @SerializedName("count")
    private int count;

    @SerializedName("redundancy")
    private int redundancy;

    public PlanEntry() {
    }

    public PlanEntry(String position, int count, int redundancy) {
        this.position = position;
        this.count = count;
        this.redundancy = redundancy;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public int getRedundancy() {
        return redundancy;
    }

    public void setRedundancy(int redundancy) {
        this.redundancy = redundancy;
    }

    public int getTotal() {
        return count + redundancy;
    }

    /**
     * Returns whether this entry has a non-zero count portion.
     */
    public boolean hasCount() {
        return count > 0;
    }

    /**
     * Returns whether this entry has a non-zero redundancy portion.
     */
    public boolean hasRedundancy() {
        return redundancy > 0;
    }
}
