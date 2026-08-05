package com.billy65536.qab.planner.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出的购物计划，包括总成本、失败的项目、警告和计划条目。
 *
 * <h3>格式版本</h3>
 * <ul>
 *   <li><b>1</b>：{@code PlanEntry} 只有 position/count/redundancy；</li>
 *   <li><b>2</b>：新增 {@code itemId}，自动购买据此查堆叠上限做背包容量预判。</li>
 * </ul>
 *
 * <p>版本 1 的计划无法用于 {@code /qab nav apply}（缺 itemId 就无法判断背包能否装下，
 * QShop 会拒绝发货），必须用 {@code /qab plan} 重新生成。</p>
 */
public class ShoppingPlan {
    /** 当前计划格式版本。自动购买要求计划版本 &gt;= 该值。 */
    public static final int FORMAT_VERSION = 2;

    @SerializedName("version")
    private int version;

    @SerializedName("totalCost")
    private double totalCost;

    @SerializedName("failed")
    private List<FailedWarnEntry> failed;

    @SerializedName("warn")
    private List<FailedWarnEntry> warn;

    @SerializedName("plan")
    private List<PlanEntry> plan;

    public ShoppingPlan() {
        this.version = FORMAT_VERSION;
        this.failed = new ArrayList<>();
        this.warn = new ArrayList<>();
        this.plan = new ArrayList<>();
    }

    /**
     * 该计划是否可用于自动购买。
     *
     * <p>要求版本号达标，且每个条目都带 {@code itemId}。
     * 只看 version 不够：手工编辑过的计划可能版本号是 2 但条目缺字段。</p>
     */
    public boolean isBuyable() {
        if (version < FORMAT_VERSION) return false;
        if (plan == null) return false;
        for (PlanEntry entry : plan) {
            if (!entry.hasItemId()) return false;
        }
        return true;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public List<FailedWarnEntry> getFailed() {
        return failed;
    }

    public void setFailed(List<FailedWarnEntry> failed) {
        this.failed = failed;
    }

    public List<FailedWarnEntry> getWarn() {
        return warn;
    }

    public void setWarn(List<FailedWarnEntry> warn) {
        this.warn = warn;
    }

    public List<PlanEntry> getPlan() {
        return plan;
    }

    public void setPlan(List<PlanEntry> plan) {
        this.plan = plan;
    }

    public void addPlanEntry(PlanEntry entry) {
        this.plan.add(entry);
    }

    public void addFailed(FailedWarnEntry entry) {
        this.failed.add(entry);
    }

    public void addWarn(FailedWarnEntry entry) {
        this.warn.add(entry);
    }

    public void addCost(double cost) {
        this.totalCost += cost;
    }
}
