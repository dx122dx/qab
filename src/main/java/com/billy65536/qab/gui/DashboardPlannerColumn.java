package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import com.billy65536.qab.QShopAutoBuyMod;
import com.billy65536.qab.planner.region.RegionManager;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * 仪表盘左列「自动规划」。
 *
 * <p>5 行「文字 + 当前选中值 + [...]按钮」：选中的数据库 / 购物清单 / 区域表 / 包 /
 * 购物计划。值实时读命令层选中状态（无缓存，渲染即最新）；点击行尾 [...] 按钮切换到
 * 对应选项卡的文件列表：数据库→DB、购物清单→LIST、区域表→REGION、包→COMPOUND、
 * 购物计划→PLAN（导航栏第 6 个选项卡，指标 q-1）。</p>
 */
public class DashboardPlannerColumn extends AbstractLayout {

    /** 列标题区高度（标题行下开始五行）。 */
    private static final int TITLE_H = 24;
    /** 行高（与 FileListView.ROW_HEIGHT 一致）。 */
    private static final int ROW_H = 26;
    /** 水平内边距。 */
    private static final int PAD = 8;
    /** 标签固定列宽（"购物清单" 4 汉字 + 余量）。 */
    private static final int LABEL_W = 76;
    /** [...] 按钮宽。 */
    private static final int BTN_W = 40;
    /** [...] 按钮文字。 */
    private static final Text BTN_TEXT = Text.literal("[...]");

    private static final int HOVER_BG = 0x18FFFFFF;
    private static final int TITLE_COLOR = 0xFFFFAA00;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int VALUE_COLOR = 0xFFFFFFFF;
    private static final int NONE_COLOR = 0xFF888888;
    private static final int BTN_COLOR = 0xFFFFFFFF;
    private static final int BTN_HOVER_COLOR = 0xFFFFAA00;

    /** 行 → 选项卡映射（[...] 点击后切换的目标）。 */
    private static final DashboardLayout.Tab[] ROW_TABS = {
            DashboardLayout.Tab.DB,
            DashboardLayout.Tab.LIST,
            DashboardLayout.Tab.REGION,
            DashboardLayout.Tab.COMPOUND,
            DashboardLayout.Tab.PLAN
    };

    /** 行标签翻译键。 */
    private static final String[] ROW_LABEL_KEYS = {
            "qab.msg.dashboard.row_db",
            "qab.msg.dashboard.row_list",
            "qab.msg.dashboard.row_region",
            "qab.msg.dashboard.row_compound",
            "qab.msg.dashboard.row_plan"
    };

    private final TextRenderer tr;
    private final DashboardLayout host;

    public DashboardPlannerColumn(TextRenderer tr, DashboardLayout host) {
        this.tr = tr;
        this.host = host;
    }

    /** 事件驱动刷新（渲染时实时读命令层状态，无缓存，故为空实现）。 */
    public void refresh() {
    }

    /** 第 {@code row} 行的当前选中值（null = 未选择）。 */
    private String rowValue(int row) {
        return switch (row) {
            case 0 -> nameOf(QShopAutoBuyMod.BUYER.getSelectedDb() != null
                    ? QShopAutoBuyMod.BUYER.getSelectedDb().getPath() : null);
            case 1 -> nameOf(QShopAutoBuyMod.BUYER.getSelectedList());
            case 2 -> RegionManager.getCurrentTableName();
            case 3 -> nameOf(QShopAutoBuyMod.BUYER.getSelectedCompound());
            case 4 -> nameOf(QShopAutoBuyMod.BUYER.getSelectedPlan());
            default -> null;
        };
    }

    private static String nameOf(Path path) {
        return path == null ? null : path.getFileName().toString();
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.drawTextWithShadow(this.tr, Text.translatable("qab.msg.dashboard.planner_title"),
                PAD, 6, TITLE_COLOR);
        int valueX = PAD + LABEL_W;
        for (int i = 0; i < ROW_TABS.length; i++) {
            int rowY = TITLE_H + i * ROW_H;
            boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hovered) {
                ctx.fill(0, rowY, this.width, rowY + ROW_H, HOVER_BG);
            }
            ctx.drawTextWithShadow(this.tr, Text.translatable(ROW_LABEL_KEYS[i]),
                    PAD, rowY + (ROW_H - 8) / 2, LABEL_COLOR);

            String value = rowValue(i);
            int valueColor = value == null ? NONE_COLOR : VALUE_COLOR;
            int maxValueW = this.width - valueX - BTN_W - PAD * 2;
            Text valueText = value == null
                    ? Text.translatable("qab.msg.dashboard.none")
                    : Text.literal(maxValueW > 0 ? this.tr.trimToWidth(value, maxValueW) : "");
            ctx.drawTextWithShadow(this.tr, valueText, valueX, rowY + (ROW_H - 8) / 2, valueColor);

            int btnX = this.width - BTN_W - PAD;
            boolean btnHovered = hovered && mouseX >= btnX && mouseX < btnX + BTN_W;
            ctx.drawCenteredTextWithShadow(this.tr, BTN_TEXT, btnX + BTN_W / 2,
                    rowY + (ROW_H - 8) / 2, btnHovered ? BTN_HOVER_COLOR : BTN_COLOR);
        }
    }

    @Override
    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int row = (int) ((mouseY - TITLE_H) / ROW_H);
        if (row < 0 || row >= ROW_TABS.length) {
            return false;
        }
        int btnX = this.width - BTN_W - PAD;
        if (mouseX >= btnX && mouseX <= btnX + BTN_W) {
            this.host.switchTab(ROW_TABS[row]);
            return true;
        }
        return false;
    }
}
