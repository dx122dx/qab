package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.toast.Messenger;
import com.billy65536.infrastructure.core.gui.toast.ToastType;
import com.billy65536.qab.QabCommands;
import com.billy65536.qab.automatic.ShoppingRunner;
import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.planner.model.ShoppingPlan;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * /qab gui 主仪表盘 Screen 壳。
 *
 * <p>布局树：{@link DashboardLayout}（大标题框 + 横向导航栏 + 垂直分割线 + 内容区），
 * 内容区为「自动规划」左列 + 「自动购物」右列，或对应选项卡的 {@link FileListView}。</p>
 *
 * <p>状态刷新为事件驱动（确认项 D）：购物启动/暂停/恢复/停止等动作后调用
 * {@link #refresh()} 重建左右列；tick 不轮询。</p>
 */
public class DashboardScreen extends ScreenContainer {

    private DashboardLayout layout;

    public DashboardScreen() {
        super(Text.translatable("qab.msg.dashboard.title"));
    }

    /** 根布局（init 后可用）。 */
    public DashboardLayout getDashboardLayout() {
        return this.layout;
    }

    /** 事件驱动刷新：重建仪表盘左右列（保持当前选项卡），并刷新选中状态显示。 */
    public void refresh() {
        if (this.layout != null) {
            this.layout.refreshContent();
        }
    }

    @Override
    protected void init() {
        if (this.isErrorState()) {
            super.init();
            return;
        }
        this.layout = new DashboardLayout(this.textRenderer, this);
        this.setLayout(this.layout);
        super.init();
        this.layout.setBounds(0, 0, this.width, this.height);
        this.layout.layout();
    }

    // ---- 事件驱动辅助：按钮动作 + 刷新（确认项 D，动作后重建左右列） ----

    /**
     * 开始自动购物：加载选中的购物计划，交给 {@link ShoppingRunner} 编排执行。
     * 校验与 {@code /qab nav apply} 一致（计划必须存在且为可购买格式）。
     */
    public void refreshAfterStart() {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        if (runner.isRunning()) {
            return; // 运行中，开始按钮不可点（防御）
        }
        Path planPath = QabCommands.getSelectedPlan();
        if (planPath == null) {
            Messenger.error(Text.translatable("qab.msg.nav_apply_no_selected_plan"));
            return;
        }
        ShoppingPlan plan = QabCommands.loadShoppingPlan(planPath);
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            Messenger.error(Text.translatable("qab.msg.nav_apply_empty", planPath.getFileName().toString()));
            return;
        }
        if (!plan.isBuyable()) {
            Messenger.error(Text.translatable("qab.msg.nav_apply_outdated",
                    planPath.getFileName().toString(),
                    plan.getVersion(), ShoppingPlan.FORMAT_VERSION));
            return;
        }
        QabConfig config = ConfigLoader.getConfig();
        int queued = runner.start(plan, config);
        if (queued <= 0) {
            Messenger.error(Text.translatable("qab.msg.nav_apply_no_target", planPath.getFileName().toString()));
            return;
        }
        Messenger.notify(Text.translatable("qab.msg.nav_apply_started",
                planPath.getFileName().toString(), queued, config.getBuyCommand()), ToastType.SUCCESS);
        this.refresh();
    }

    /** 切换暂停/恢复（按钮为开关，仅运行中可点）。 */
    public void refreshAfterPauseToggle() {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        if (!runner.isRunning()) {
            return;
        }
        if (runner.isPaused()) {
            if (runner.resume()) {
                Messenger.notify(Text.translatable("qab.msg.nav_resumed"), ToastType.SUCCESS);
            }
        } else {
            if (runner.pause()) {
                Messenger.notify(Text.translatable("qab.msg.nav_paused"), ToastType.WARN);
            }
        }
        this.refresh();
    }

    /** 停止自动购物（仅运行中可点）。 */
    public void refreshAfterStop() {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        if (!runner.isRunning()) {
            return;
        }
        runner.stop();
        Messenger.notify(Text.translatable("qab.msg.nav_stop_done", 0), ToastType.WARN);
        this.refresh();
    }

    /** 获取客户端（便捷入口，布局组件可用）。 */
    public static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }
}
