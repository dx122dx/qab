package com.billy65536.qab.planning.model;

import com.google.gson.annotations.SerializedName;

/**
 * 从 ChunkScanner 导出的单个商店条目。
 * 代表商店位置的一个商品列表。
 */
public class ShopExportEntry {
    @SerializedName("dimId")
    private String dimId;

    @SerializedName("x")
    private int x;

    @SerializedName("y")
    private int y;

    @SerializedName("z")
    private int z;

    @SerializedName("owner")
    private String owner;

    @SerializedName("mode")
    private int mode;

    @SerializedName("quantity")
    private int quantity;

    @SerializedName("itemId")
    private String itemId;

    @SerializedName("itemName")
    private String itemName;

    @SerializedName("price")
    private int price;

    @SerializedName("detailNbtString")
    private String detailNbtString;

    @SerializedName("flags")
    private int flags;

    @SerializedName("timestamp")
    private long timestamp;

    /** Special sentinel for infinite stock (system shops) */
    public static final int INFINITE_STOCK = 0xFFFFFF;

    /** Mode: shop is selling items */
    public static final int MODE_SELL = 0;

    /** Mode: shop is buying items */
    public static final int MODE_BUY = 1;

    public ShopExportEntry() {
    }

    public String getDimId() {
        return dimId;
    }

    public void setDimId(String dimId) {
        this.dimId = dimId;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getZ() {
        return z;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    /**
     * Returns the price in minimal currency units (cents).
     * Real price = price / 100.0.
     */
    public int getPrice() {
        return price;
    }

    public void setPrice(int price) {
        this.price = price;
    }

    public String getDetailNbtString() {
        return detailNbtString;
    }

    public void setDetailNbtString(String detailNbtString) {
        this.detailNbtString = detailNbtString;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Returns the real price (price / 100.0).
     */
    public double getRealPrice() {
        return price / 100.0;
    }

    /**
     * Returns the effective available quantity, accounting for infinite stock.
     */
    public int getEffectiveQuantity() {
        return quantity == INFINITE_STOCK ? Integer.MAX_VALUE : quantity;
    }

    /**
     * Whether this is a sell-mode shop (player can buy from).
     */
    public boolean isSellMode() {
        return mode == MODE_SELL;
    }

    /**
     * Returns true if stock is infinite (system shop).
     */
    public boolean isInfiniteStock() {
        return quantity == INFINITE_STOCK;
    }

    /**
     * Returns a formatted position string like "minecraft:overworld(12,65,13)".
     */
    public String getPositionString() {
        return dimId + "(" + x + "," + y + "," + z + ")";
    }

    /**
     * Returns the effective stock available.
     * For infinite stock, returns the requested amount up to Integer.MAX_VALUE.
     */
    public int getAvailableQuantity(int requested) {
        if (isInfiniteStock()) {
            return requested;
        }
        return Math.min(quantity, requested);
    }

    @Override
    public String toString() {
        return String.format("%s @ %s: %s x%d price=%.2f mode=%d",
                itemId, getPositionString(), itemName, quantity, getRealPrice(), mode);
    }
}
