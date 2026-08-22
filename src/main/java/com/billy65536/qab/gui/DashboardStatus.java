package com.billy65536.qab.gui;

import com.billy65536.qab.QabCommands;
import com.billy65536.qab.automatic.ShoppingRunner;
import com.billy65536.qab.integration.CsNavigationHelper;
import com.billy65536.qab.planner.region.RegionManager;

/**
 * 仪表盘状态模型（右列「自动购物」动态状态计算）。
 *
 * <p>状态（确认项 q-2）：未运行且配置齐全 = 全功能；缺选中项 = 缺少xxx；
 * 运行中 = 运行中；已暂停 = 已暂停；等待维度 = 等待维度。
 * Baritone 不可用时追加「仅导航[i]」标记。</p>
 */
public final class DashboardStatus {

    /** 状态枚举。 */
    public enum State {
        /** 未运行且选中齐全。 */
        READY,
        /** 未运行但缺少选中项（missingLabel 指明缺哪个）。 */
        MISSING,
        /** 运行中。 */
        RUNNING,
        /** 已暂停。 */
        PAUSED,
        /** 等待维度。 */
        WAITING_DIMENSION
    }

    /** 计算当前状态（运行态判定用 ShoppingRunner；未运行判定缺项）。 */
    public static State computeState() {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        if (runner.isRunning()) {
            if (runner.getAwaitingDimension() != null) {
                return State.WAITING_DIMENSION;
            }
            return runner.isPaused() ? State.PAUSED : State.RUNNING;
        }
        if (missingLabel() != null) {
            return State.MISSING;
        }
        return State.READY;
    }

    /**
     * 返回缺失选中项的中文标签（null = 齐全）。优先显示最重要的缺失项。
     */
    public static String missingLabel() {
        if (QabCommands.getSelectedDb() == null) {
            return "缺少数据库";
        }
        if (QabCommands.getSelectedList() == null) {
            return "缺少购物清单";
        }
        if (RegionManager.getCurrentTableName() == null) {
            return "缺少区域表";
        }
        if (QabCommands.getSelectedCompound() == null) {
            return "缺少包";
        }
        if (QabCommands.getSelectedPlan() == null) {
            return "缺少购物计划";
        }
        return null;
    }

    /** Baritone 是否可用（决定是否显示「仅导航[i]」提示）。 */
    public static boolean baritoneAvailable() {
        return CsNavigationHelper.isBaritoneAvailable();
    }

    private DashboardStatus() {
    }
}
