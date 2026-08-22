package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import com.billy65536.qab.automatic.ShoppingRunner;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 仪表盘右列「自动购物」。
 *
 * <p>包含：动态状态行（{@link DashboardStatus}，确认项 q-2；Baritone 不可用时追加
 * 「仅导航[i]」并提供悬浮文本说明）、「当前任务：」三行（已完成 / 进行中 / 失败）、
 * 垂直等宽三按钮（开始导航 / 暂停导航↔恢复导航 / 停止导航，确认项 q-3、确认项 C）。</p>
 *
 * <p>动作通过 {@link DashboardScreen} 的 {@code refreshAfter*} 执行并事件刷新
 * （确认项 D，tick 不轮询）。</p>
 */
public class DashboardShoppingColumn extends AbstractLayout {

    /** 列标题区高度。 */
    private static final int TITLE_H = 24;
    /** 状态行 top。 */
    private static final int STATUS_Y = TITLE_H + 2;
    /** 状态行高。 */
    private static final int STATUS_H = 20;
    /** "当前任务："小标题 top。 */
    private static final int TASKS_LABEL_Y = STATUS_Y + STATUS_H + 4;
    /** 任务三行 top。 */
    private static final int TASKS_Y = TASKS_LABEL_Y + 14;
    /** 任务行高。 */
    private static final int TASK_ROW_H = 22;
    /** 按钮区 top。 */
    private static final int BTN_AREA_Y = TASKS_Y + 3 * TASK_ROW_H + 8;
    /** 按钮间距。 */
    private static final int BTN_GAP = 8;
    /** 水平内边距。 */
    private static final int PAD = 8;
    /** 按钮最小高度。 */
    private static final int BTN_MIN_H = 20;

    private static final int TITLE_COLOR = 0xFFFFAA00;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int VALUE_COLOR = 0xFFFFFFFF;
    private static final int WARN_COLOR = 0xFFFFAA00;
    private static final int BTN_BG = 0x40303030;
    private static final int BTN_BORDER = 0xFF555555;
    private static final int BTN_HOVER_BORDER = 0xFFFFAA00;
    private static final int BTN_DISABLED_COLOR = 0xFF666666;
    private static final int BTN_TEXT_COLOR = 0xFFFFFFFF;

    private static final String[] TASK_LABEL_KEYS = {
            "qab.msg.dashboard.task_done",
            "qab.msg.dashboard.task_inprogress",
            "qab.msg.dashboard.task_failed"
    };

    private final TextRenderer tr;
    private final DashboardLayout host;

    public DashboardShoppingColumn(TextRenderer tr, DashboardLayout host) {
        this.tr = tr;
        this.host = host;
    }

    /** 事件驱动刷新（渲染时实时读状态，无缓存，故为空实现）。 */
    public void refresh() {
    }

    // ---- 状态行 ----

    /** 状态行文本："状态:" + 具体状态（MISSING 时显示缺失项中文标签）。 */
    private Text stateText() {
        DashboardStatus.State st = DashboardStatus.computeState();
        Text detail;
        if (st == DashboardStatus.State.MISSING) {
            detail = Text.literal(DashboardStatus.missingLabel());
        } else {
            detail = Text.translatable("qab.msg.dashboard.state_" + st.name().toLowerCase());
        }
        return Text.translatable("qab.msg.dashboard.state_prefix").append(detail);
    }

    // ---- 按钮 ----

    /** 三个按钮的可用性（与状态挂钩）。 */
    private boolean btnEnabled(int idx) {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        return switch (idx) {
            case 0 -> !runner.isRunning() && DashboardStatus.missingLabel() == null;
            case 1, 2 -> runner.isRunning();
            default -> false;
        };
    }

    /** 按钮文本（暂停按钮为开关切换，确认项 q-3）。 */
    private Text btnText(int idx) {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        return switch (idx) {
            case 0 -> Text.translatable("qab.msg.dashboard.btn_start");
            case 1 -> runner.isPaused()
                    ? Text.translatable("qab.msg.dashboard.btn_resume")
                    : Text.translatable("qab.msg.dashboard.btn_pause");
            case 2 -> Text.translatable("qab.msg.dashboard.btn_stop");
            default -> Text.literal("");
        };
    }

    private boolean btnHovered(int idx, double mouseX, double mouseY) {
        int btnH = computeBtnH();
        int y = BTN_AREA_Y + idx * (btnH + BTN_GAP);
        return mouseY >= y && mouseY < y + btnH && mouseX >= PAD && mouseX < this.width - PAD;
    }

    /** 三按钮等分剩余高度（垂直等宽，确认项 C）。 */
    private int computeBtnH() {
        int avail = this.height - BTN_AREA_Y - PAD - BTN_GAP * 2;
        return Math.max(BTN_MIN_H, avail / 3);
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 列标题
        ctx.drawTextWithShadow(this.tr, Text.translatable("qab.msg.dashboard.shopping_title"),
                PAD, 6, TITLE_COLOR);

        // 状态行
        int statusTextY = STATUS_Y + (STATUS_H - 8) / 2;
        Text stateText = stateText();
        ctx.drawTextWithShadow(this.tr, stateText, PAD, statusTextY, VALUE_COLOR);
        int x = PAD + this.tr.getWidth(stateText) + 8;
        if (!DashboardStatus.baritoneAvailable()) {
            Text navOnly = Text.translatable("qab.msg.dashboard.nav_only");
            ctx.drawTextWithShadow(this.tr, navOnly, x, statusTextY, WARN_COLOR);
            int iw = this.tr.getWidth(navOnly);
            if (mouseY >= STATUS_Y && mouseY < STATUS_Y + STATUS_H
                    && mouseX >= x && mouseX < x + iw) {
                ctx.drawTooltip(this.tr,
                        List.of(Text.translatable("qab.msg.dashboard.nav_only_tooltip")),
                        mouseX, mouseY);
            }
        }

        // "当前任务：" + 三行
        ctx.drawTextWithShadow(this.tr, Text.translatable("qab.msg.dashboard.tasks_label"),
                PAD, TASKS_LABEL_Y, LABEL_COLOR);
        ShoppingRunner runner = ShoppingRunner.getInstance();
        int[] counts = {runner.getCompletedTasks(), runner.remaining(), runner.getSkippedTasks()};
        for (int i = 0; i < 3; i++) {
            int y = TASKS_Y + i * TASK_ROW_H;
            Text label = Text.translatable(TASK_LABEL_KEYS[i]);
            ctx.drawTextWithShadow(this.tr, label, PAD + 16, y + (TASK_ROW_H - 8) / 2, LABEL_COLOR);
            ctx.drawTextWithShadow(this.tr, Text.literal(String.valueOf(counts[i])),
                    PAD + 16 + this.tr.getWidth(label) + 16, y + (TASK_ROW_H - 8) / 2, VALUE_COLOR);
        }

        // 垂直等宽三按钮
        int btnH = computeBtnH();
        for (int i = 0; i < 3; i++) {
            int y = BTN_AREA_Y + i * (btnH + BTN_GAP);
            boolean enabled = btnEnabled(i);
            int border = enabled
                    ? (btnHovered(i, mouseX, mouseY) ? BTN_HOVER_BORDER : BTN_BORDER)
                    : BTN_DISABLED_COLOR;
            ctx.fill(PAD, y, this.width - PAD, y + btnH, BTN_BG);
            ctx.fill(PAD, y, PAD + 1, y + btnH, border);
            ctx.fill(this.width - PAD - 1, y, this.width - PAD, y + btnH, border);
            ctx.fill(PAD, y, this.width - PAD, y + 1, border);
            ctx.fill(PAD, y + btnH - 1, this.width - PAD, y + btnH, border);
            ctx.drawCenteredTextWithShadow(this.tr, btnText(i), this.width / 2,
                    y + (btnH - 8) / 2, enabled ? BTN_TEXT_COLOR : BTN_DISABLED_COLOR);
        }
    }

    @Override
    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int btnH = computeBtnH();
        for (int i = 0; i < 3; i++) {
            int y = BTN_AREA_Y + i * (btnH + BTN_GAP);
            if (mouseY >= y && mouseY < y + btnH && mouseX >= PAD && mouseX < this.width - PAD) {
                if (!btnEnabled(i)) {
                    return false;
                }
                switch (i) {
                    case 0 -> this.host.getScreen().refreshAfterStart();
                    case 1 -> this.host.getScreen().refreshAfterPauseToggle();
                    case 2 -> this.host.getScreen().refreshAfterStop();
                }
                return true;
            }
        }
        return false;
    }
}
