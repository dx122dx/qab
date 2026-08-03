package com.billy65536.qab.planner.model;

import com.google.gson.annotations.SerializedName;

/**
 * 表示计划输出中失败或警告的项目。
 * 对于失败的项目：无法完全满足的项目（计数 = 剩余）。
 * 对于警告项目：冗余部分未完全满足的项目。
 */
public class FailedWarnEntry {
    @SerializedName("item")
    private ShoppingItem item;

    @SerializedName("count")
    private int count;

    @SerializedName("redundancy")
    private int redundancy;

    public FailedWarnEntry() {
    }

    public FailedWarnEntry(ShoppingItem item, int count, int redundancy) {
        this.item = item;
        this.count = count;
        this.redundancy = redundancy;
    }

    public ShoppingItem getItem() {
        return item;
    }

    public void setItem(ShoppingItem item) {
        this.item = item;
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
}
