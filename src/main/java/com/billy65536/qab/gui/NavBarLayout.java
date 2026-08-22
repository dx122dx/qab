package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 横向导航栏组件（5 个可见选项卡）。
 *
 * <p>{@link DashboardLayout.Tab#SCHEMATIC} 为隐藏视图：不渲染、不响应点击
 * （构造时从可见列表过滤），仅由 dashboard 内部流程（原理图生成）切换。</p>
 *
 * <p>选中项样式（确认项 A）：半透明金色高亮背景 + 表头金色文字，无边框。
 * 选项卡文本水平排列、居中，点击切换。文本来自语言文件
 * （{@code qab.msg.dashboard.tab.*}）。</p>
 */
public class NavBarLayout extends AbstractLayout {

    /** 选项卡内边距（左右）。 */
    private static final int PAD = 14;
    /** 选中项高亮背景（半透明金色，与 TableLayout 行高亮一致）。 */
    private static final int HIGHLIGHT_BG = 0x60FFAA00;
    /** 选中文字颜色（表头金色）。 */
    private static final int SELECTED_COLOR = 0xFFFFAA00;
    /** 普通文字颜色。 */
    private static final int NORMAL_COLOR = 0xFFFFFFFF;
    /** 悬停背景（半透明白）。 */
    private static final int HOVER_BG = 0x18FFFFFF;
    /** 导航栏底部分隔线颜色。 */
    private static final int LINE_COLOR = 0xFFFFAA00;

    private final TextRenderer tr;
    private final DashboardLayout host;
    private DashboardLayout.Tab selectedTab;

    /** 可见选项卡（按 Tab 枚举序，过滤隐藏的 SCHEMATIC）。 */
    private final DashboardLayout.Tab[] tabs;
    /** 各选项卡的显示文本（与 {@link #tabs} 对齐）。 */
    private final Text[] labels;
    /** 各选项卡的 x 起点与宽度（layout 时计算）。 */
    private final List<int[]> bounds = new ArrayList<>();

    public NavBarLayout(TextRenderer tr, DashboardLayout host, DashboardLayout.Tab selectedTab) {
        this.tr = tr;
        this.host = host;
        this.selectedTab = selectedTab;
        this.tabs = Arrays.stream(DashboardLayout.Tab.values())
                .filter(t -> t != DashboardLayout.Tab.SCHEMATIC)
                .toArray(DashboardLayout.Tab[]::new);
        this.labels = new Text[this.tabs.length];
        for (int i = 0; i < this.tabs.length; i++) {
            this.labels[i] = Text.translatable("qab.msg.dashboard.tab."
                    + this.tabs[i].name().toLowerCase());
        }
    }

    /** 更新选中选项卡（渲染反映高亮）。 */
    public void setSelectedTab(DashboardLayout.Tab tab) {
        this.selectedTab = tab;
    }

    /** 返回指定 x 处的选项卡索引（-1 = 未命中）。 */
    public int tabAtX(double mouseX) {
        for (int i = 0; i < this.bounds.size(); i++) {
            int[] b = this.bounds.get(i);
            if (mouseX >= b[0] && mouseX < b[0] + b[1]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void layout() {
        this.bounds.clear();
        int x = 0;
        for (Text label : this.labels) {
            int w = this.tr.getWidth(label) + PAD * 2;
            this.bounds.add(new int[]{x, w});
            x += w;
        }
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 导航栏背景
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
        int y0 = 0;
        int y1 = this.height;
        for (int i = 0; i < this.tabs.length; i++) {
            int[] b = this.bounds.get(i);
            int tabX = b[0];
            int tabW = b[1];
            boolean selected = this.tabs[i] == this.selectedTab;
            boolean hovered = mouseY >= 0 && mouseY < this.height
                    && mouseX >= tabX && mouseX < tabX + tabW;
            if (selected) {
                ctx.fill(tabX, y0, tabX + tabW, y1, HIGHLIGHT_BG);
            } else if (hovered) {
                ctx.fill(tabX, y0, tabX + tabW, y1, HOVER_BG);
            }
            int color = selected ? SELECTED_COLOR : NORMAL_COLOR;
            int textX = tabX + (tabW - this.tr.getWidth(this.labels[i])) / 2;
            ctx.drawTextWithShadow(this.tr, this.labels[i], textX,
                    (this.height - 8) / 2, color);
        }
        // 导航栏底部分隔线
        ctx.fill(0, this.height - 1, this.width, this.height, LINE_COLOR);
    }

    @Override
    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (mouseY < 0 || mouseY >= this.height) {
            return false;
        }
        int idx = this.tabAtX(mouseX);
        if (idx < 0) {
            return false;
        }
        DashboardLayout.Tab tab = this.tabs[idx];
        this.host.switchTab(tab);
        return true;
    }
}
