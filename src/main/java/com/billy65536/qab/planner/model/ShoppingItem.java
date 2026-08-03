package com.billy65536.qab.planner.model;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * 购物清单项。
 */
public class ShoppingItem {
    @SerializedName("id")
    private String id;

    @SerializedName("count")
    private int count;

    @SerializedName("enchant")
    private Map<String, Integer> enchant;

    @SerializedName("matchNbt")
    private String matchNbt;

    @SerializedName("maxAffordable")
    private Double maxAffordable;

    public ShoppingItem() {
    }

    public ShoppingItem(String id, int count) {
        this.id = id;
        this.count = count;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Map<String, Integer> getEnchant() {
        return enchant;
    }

    public void setEnchant(Map<String, Integer> enchant) {
        this.enchant = enchant;
    }

    public String getMatchNbt() {
        return matchNbt;
    }

    public void setMatchNbt(String matchNbt) {
        this.matchNbt = matchNbt;
    }

    public Double getMaxAffordable() {
        return maxAffordable;
    }

    public void setMaxAffordable(Double maxAffordable) {
        this.maxAffordable = maxAffordable;
    }

    public boolean hasEnchant() {
        return enchant != null && !enchant.isEmpty();
    }

    public boolean hasMatchNbt() {
        return matchNbt != null && !matchNbt.isEmpty();
    }

    public boolean hasMaxAffordable() {
        return maxAffordable != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(id).append(" x").append(count);
        if (hasEnchant()) {
            sb.append(" [ench:").append(enchant).append("]");
        }
        if (hasMatchNbt()) {
            sb.append(" [nbt:").append(matchNbt).append("]");
        }
        return sb.toString();
    }
}
