package com.billy65536.qab.planning.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出的购物计划，包括总成本、失败的项目、警告和计划条目。
 */
public class ShoppingPlan {
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
        this.version = 1;
        this.failed = new ArrayList<>();
        this.warn = new ArrayList<>();
        this.plan = new ArrayList<>();
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
