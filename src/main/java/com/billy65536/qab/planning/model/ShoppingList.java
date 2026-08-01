package com.billy65536.qab.planning.model;

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

    public List<ShoppingItem> getItems() {
        return items;
    }

    public void setItems(List<ShoppingItem> items) {
        this.items = items;
    }
}
