package com.billy65536.qab.planner.model;

import com.google.gson.annotations.SerializedName;

/**
 * 购物计划中的一条记录，表示在特定商店购买的物品。
 *
 * <p><b>格式版本 2 起新增 {@code itemId}</b>：自动购买时需要据此查询物品堆叠上限
 * （{@code Item.getMaxCount()}），才能算出「买 N 个要占几格背包」。
 * 缺少该字段无法做容量预判，QShop 会因背包不足拒绝发货。
 * 因此 {@link ShoppingPlan#FORMAT_VERSION} &lt; 2 的旧计划必须重新生成。</p>
 */
public class PlanEntry {
    @SerializedName("position")
    private String position;

    /** 商品的物品 ID（如 {@code minecraft:stone}），用于查询堆叠上限做容量预判。 */
    @SerializedName("itemId")
    private String itemId;

    @SerializedName("count")
    private int count;

    @SerializedName("redundancy")
    private int redundancy;

    public PlanEntry() {
    }

    public PlanEntry(String position, String itemId, int count, int redundancy) {
        this.position = position;
        this.itemId = itemId;
        this.count = count;
        this.redundancy = redundancy;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    /** 该条目是否带有可用的物品 ID（用于校验旧格式计划）。 */
    public boolean hasItemId() {
        return itemId != null && !itemId.isBlank();
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
