package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import com.billy65536.qab.QabCommands;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import org.jetbrains.annotations.Nullable;

/**
 * /qab gui 仪表盘根布局。
 *
 * <p>负责：大标题框（"QShop Auto Buy 仪表盘"，居中金色 + 分隔线）、横向导航栏
 * （{@link NavBarLayout}，6 选项卡）、内容区（仪表盘视图 = 左右两列，或对应选项卡的
 * {@link FileListView}）、左右列之间的垂直分割线。</p>
 *
 * <p>坐标（局部坐标系）：标题框顶部 → 导航栏 → 内容区占满剩余高度。</p>
 */
public class DashboardLayout extends AbstractLayout {

    /** 大标题框高度。 */
    public static final int TITLE_H = 48;
    /** 导航栏高度。 */
    public static final int NAV_H = 26;
    /** 标题下方金色分隔线高度（与 FileListScreen 一致）。 */
    public static final int TITLE_LINE_H = 1;
    /** 垂直分割线宽度。 */
    public static final int DIVIDER_W = 1;

    /** 大标题文字颜色。 */
    public static final int TITLE_COLOR = 0xFFFFFFFF;
    /** 标题分隔线颜色（表头金色）。 */
    public static final int LINE_COLOR = 0xFFFFAA00;
    /** 垂直分割线颜色。 */
    public static final int DIVIDER_COLOR = 0xFF888888;

    /** 选项卡枚举（6 个，含购物计划）。 */
    public enum Tab {
        DASHBOARD, DB, LIST, REGION, COMPOUND, PLAN
    }

    private final TextRenderer tr;
    private final DashboardScreen screen;

    /** 当前选项卡。 */
    private Tab selectedTab = Tab.DASHBOARD;

    private NavBarLayout navBar;
    private DashboardPlannerColumn plannerColumn;
    private DashboardShoppingColumn shoppingColumn;
    /** 非仪表盘选项卡对应的嵌入文件列表（null = 仪表盘视图，切换时挂载/卸载）。 */
    @Nullable
    private FileListView listView;

    public DashboardLayout(TextRenderer tr, DashboardScreen screen) {
        this.tr = tr;
        this.screen = screen;
    }

    public Tab getSelectedTab() {
        return this.selectedTab;
    }

    public DashboardScreen getScreen() {
        return this.screen;
    }

    public TextRenderer textRenderer() {
        return this.tr;
    }

    /** 事件驱动刷新内容区（重建左右列数据展示，保持当前选项卡）。 */
    public void refreshContent() {
        if (this.selectedTab == Tab.DASHBOARD) {
            if (this.plannerColumn != null) {
                this.plannerColumn.refresh();
            }
            if (this.shoppingColumn != null) {
                this.shoppingColumn.refresh();
            }
        }
    }

    /** 切换选项卡：仪表盘 ↔ 嵌入文件列表（非仪表盘选项卡 → 内容区 FileListView）。 */
    public void switchTab(Tab tab) {
        if (tab == this.selectedTab) {
            return;
        }
        this.selectedTab = tab;
        if (this.navBar != null) {
            this.navBar.setSelectedTab(tab);
        }
        if (tab == Tab.DASHBOARD) {
            // 卸载嵌入文件列表，回到左右列双视图
            if (this.listView != null) {
                if (this.children != null) {
                    this.children.remove(this.listView);
                }
                this.listView = null;
            }
            this.refreshContent();
        } else {
            this.ensureListTab();
        }
        this.layout();
    }

    /**
     * 重建/刷新当前选项卡的嵌入文件列表（compound 保存成功后重扫目录，保持滚动与高亮）。
     * 仪表盘视图下不执行。
     */
    public void reloadList() {
        if (this.selectedTab == Tab.DASHBOARD) {
            return;
        }
        this.ensureListTab();
    }

    /** 挂载或刷新当前选项卡的嵌入文件列表（复用命令层扫描/选中/保存回调）。 */
    private void ensureListTab() {
        QabCommands.DashboardListConfig cfg = QabCommands.dashboardListConfig(this.selectedTab);
        if (this.listView == null) {
            this.listView = new FileListView(this.tr, cfg.actions(), cfg.entries(), cfg.callbacks());
            this.addChild(this.listView);
        } else {
            this.listView.setEntries(cfg.entries());
            this.listView.setHighlightedRowByPath(cfg.highlight());
        }
    }

    @Override
    public void init() {
        this.children = null;
        this.addChild(this.navBar = new NavBarLayout(this.tr, this, this.selectedTab));
        this.addChild(this.plannerColumn = new DashboardPlannerColumn(this.tr, this));
        this.addChild(this.shoppingColumn = new DashboardShoppingColumn(this.tr, this));
        super.init();
    }

    /** 排布：导航栏在标题框下方，内容区占满剩余（仪表盘视图左右两列各占一半）。 */
    @Override
    public void layout() {
        if (this.navBar == null) {
            return;
        }
        this.navBar.setBounds(0, TITLE_H + TITLE_LINE_H, this.width, NAV_H);
        this.navBar.layout();
        int contentY = TITLE_H + TITLE_LINE_H + NAV_H;
        int contentH = this.height - contentY;
        if (this.listView != null) {
            this.listView.setBounds(0, contentY, this.width, contentH);
            this.listView.layout();
            return;
        }
        if (this.plannerColumn != null) {
            this.plannerColumn.setBounds(0, contentY, this.width / 2 - DIVIDER_W / 2, contentH);
            this.plannerColumn.layout();
        }
        if (this.shoppingColumn != null) {
            this.shoppingColumn.setBounds(this.width / 2 + DIVIDER_W / 2, contentY,
                    this.width / 2 - DIVIDER_W / 2, contentH);
            this.shoppingColumn.layout();
        }
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 大标题框背景（半透明深色）
        ctx.fill(0, 0, this.width, TITLE_H, 0xC0101010);
        // 大标题（居中白色，1x 即可；视觉占位为标题框）
        ctx.drawCenteredTextWithShadow(this.tr, Text.translatable("qab.msg.dashboard.title"),
                this.width / 2, (TITLE_H - 9) / 2, TITLE_COLOR);
        // 标题下方金色分隔线
        ctx.fill(0, TITLE_H, this.width, TITLE_H + TITLE_LINE_H, LINE_COLOR);
        // 垂直分割线：仅仪表盘视图绘制（左右列之间）
        if (this.listView == null && this.plannerColumn != null && this.shoppingColumn != null) {
            int dividerX = this.width / 2;
            ctx.fill(dividerX - DIVIDER_W / 2, TITLE_H + TITLE_LINE_H + NAV_H,
                    dividerX + DIVIDER_W / 2, this.height, DIVIDER_COLOR);
        }
    }
}
