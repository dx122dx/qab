package com.billy65536.qab.planner.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 JSON 加载的购物清单。
 */
public class ShoppingList {
    @SerializedName("version")
    private int version;

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("redundancy")
    private int redundancy;

    /** 全局数量倍率（默认 1.0，不放大）；购买量计算时每项需求 = round(需求数 × 倍率)。 */
    @SerializedName("multiplier")
    private double multiplier = 1.0;

    /** 全局冗余率（百分比数值，默认 0.0）；每项比率冗余 = round(需求 × 冗余率 / 100)。 */
    @SerializedName("redundancyPercent")
    private double redundancyPercent = 0.0;

    @SerializedName("items")
    private List<ShoppingItem> items;

    public ShoppingList() {
        this.items = new ArrayList<>();
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getRedundancy() {
        return redundancy;
    }

    public void setRedundancy(int redundancy) {
        this.redundancy = redundancy;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public double getRedundancyPercent() {
        return redundancyPercent;
    }

    public void setRedundancyPercent(double redundancyPercent) {
        this.redundancyPercent = redundancyPercent;
    }

    public List<ShoppingItem> getItems() {
        return items;
    }

    public void setItems(List<ShoppingItem> items) {
        this.items = items;
    }
}
