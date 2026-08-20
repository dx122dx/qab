package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.layout.MultiLineTextCell;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;
import com.billy65536.qab.planner.model.FailedWarnEntry;
import com.billy65536.qab.planner.model.PlanEntry;
import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingPlan;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 购物计划查看界面（ScreenContainer + TableLayout）。
 *
 * <p>顶部页签「计划条目 | 失败 N | 警告 M」切换数据视图，表格区域复用，无行内编辑。
 * 标题取 plan.name，缺失回退到传入计划名/翻译键；标题右侧「编辑」按钮进入
 * {@link MetaEditScreen}；底部「返回」与「编辑为购物清单」（按 itemId 聚合为
 * 临时清单跳转 {@link ShoppingListScreen}）。</p>
 */
public class PlanScreen extends ScreenContainer {
    public static final int HEADER_Y = 56;
    public static final int TAB_ROW_Y = 28;
    public static final int TAB_ROW_H = 20;
    public static final int TAB_PAD = 10;
    public static final int TAB_GAP = 10;
    public static final int FOOTER_H = 28;
    public static final int ROW_HEIGHT = 26;
    public static final int SEQ_W = 34;
    public static final int ICON_W = 20;

    private static final int COLOR_GRAY = 0xFFAAAAAA;
    private static final int COLOR_YELLOW = 0xFFFFFF55;
    private static final int COLOR_LIGHT_BLUE = 0xFFAACCFF;

    private enum Tab { PLAN, FAILED, WARN }

    private final ShoppingPlan plan;
    private final String planName;
    private final Screen parent;
    private final PlanListSource planSource;

    private Tab activeTab = Tab.PLAN;
    private TableLayout layout;

    public PlanScreen(ShoppingPlan plan, String planName, Screen parent) {
        super(Text.literal(listTitle(plan, planName)));
        this.plan = plan;
        this.planName = planName;
        this.parent = parent;
        this.planSource = new PlanListSource(plan);
    }

    private static String listTitle(ShoppingPlan plan, String planName) {
        String name = plan.getName();
        if (name != null && !name.isBlank()) return name;
        if (planName != null && !planName.isBlank()) return planName;
        return Text.translatable("qab.msg.plan_gui.title").getString();
    }

    /* ---- 页签 ---- */

    private String tabLabel(Tab tab) {
        return switch (tab) {
            case PLAN -> Text.translatable("qab.msg.plan_gui.tab_plan").getString();
            case FAILED -> Text.translatable("qab.msg.plan_gui.tab_failed",
                    this.plan.getFailed() == null ? 0 : this.plan.getFailed().size()).getString();
            case WARN -> Text.translatable("qab.msg.plan_gui.tab_warn",
                    this.plan.getWarn() == null ? 0 : this.plan.getWarn().size()).getString();
        };
    }

    private int[] tabRect(Tab tab) {
        int x = 8;
        for (Tab t : Tab.values()) {
            if (t == tab) break;
            x += this.textRenderer.getWidth(this.tabLabel(t)) + TAB_PAD * 2 + TAB_GAP;
        }
        int w = this.textRenderer.getWidth(this.tabLabel(tab)) + TAB_PAD * 2;
        return new int[]{x, TAB_ROW_Y, w, TAB_ROW_H};
    }

    private Tab hitTab(double mouseX, double mouseY) {
        if (mouseY < TAB_ROW_Y || mouseY >= TAB_ROW_Y + TAB_ROW_H) return null;
        for (Tab tab : Tab.values()) {
            int[] r = this.tabRect(tab);
            if (mouseX >= r[0] && mouseX < r[0] + r[2]) return tab;
        }
        return null;
    }

    /* ---- 标题「编辑」按钮 ---- */

    private int[] editBtnRect() {
        return TitleEditButton.rect(this.textRenderer, this.width, "qab.msg.plan_gui.edit");
    }

    private boolean hitEditBtn(double mouseX, double mouseY) {
        return TitleEditButton.hit(mouseX, mouseY, this.editBtnRect());
    }

    private void openMetaEdit() {
        this.client.send(() -> this.client.setScreen(new MetaEditScreen(this.planSource, this)));
    }

    /* ---- 布局 ---- */

    @Override
    protected void init() {
        if (this.isErrorState()) {
            super.init();
            return;
        }
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.back"), b -> this.closeScreen())
                .dimensions(8, this.height - 24, 80, 20).build());
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.plan_gui.edit_as_list"), b -> this.editAsList())
                .dimensions(this.width - 108, this.height - 24, 100, 20).build());

        this.layout = this.buildLayout();
        this.setLayout(this.layout);
        super.init();
        this.layout.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y - FOOTER_H);
        this.layout.reflow(this.width);
    }

    private void rebuildKeepScroll() {
        int scroll = this.layout.getScrollOffset();
        this.layout = this.buildLayout();
        this.layout.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y - FOOTER_H);
        this.layout.reflow(this.width);
        this.layout.setScrollOffset(scroll);
        this.setLayout(this.layout);
    }

    private TableLayout buildLayout() {
        return this.activeTab == Tab.PLAN ? this.buildPlanLayout() : this.buildFailedWarnLayout();
    }

    /** 计划条目页签：序号/图标/名称/ID/位置/需求/冗余。 */
    private TableLayout buildPlanLayout() {
        String[] headers = {
                Text.translatable("qab.msg.list_gui.h_seq").getString(),
                Text.translatable("qab.msg.list_gui.h_item").getString(),
                Text.translatable("qab.msg.list_gui.h_name").getString(),
                Text.translatable("qab.msg.list_gui.h_id").getString(),
                Text.translatable("qab.msg.plan_gui.h_pos").getString(),
                Text.translatable("qab.msg.list_gui.h_need").getString(),
                Text.translatable("qab.msg.list_gui.h_redun").getString(),
        };
        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofFixed(SEQ_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofFixed(ICON_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofWeight(7, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(120),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.LEFT),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.LEFT),
                TableLayout.ColumnSpec.ofWeight(4, TableLayout.ColumnSpec.Align.CENTER).floorWidth(48),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.CENTER),
        };
        TableLayoutBuilder builder = new TableLayoutBuilder(this.textRenderer, headers, specs, ROW_HEIGHT)
                .rowSeparator(0x22FFFFFF, 2);
        List<PlanEntry> entries = this.plan.getPlan();
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                PlanEntry entry = entries.get(i);
                if (entry == null) continue;
                this.appendPlanRow(builder, entry, i);
            }
        }
        return builder.build();
    }

    /** 失败/警告页签：序号/图标/名称/ID/数量/冗余。 */
    private TableLayout buildFailedWarnLayout() {
        String[] headers = {
                Text.translatable("qab.msg.list_gui.h_seq").getString(),
                Text.translatable("qab.msg.list_gui.h_item").getString(),
                Text.translatable("qab.msg.list_gui.h_name").getString(),
                Text.translatable("qab.msg.list_gui.h_id").getString(),
                Text.translatable("qab.msg.plan_gui.h_count").getString(),
                Text.translatable("qab.msg.list_gui.h_redun").getString(),
        };
        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofFixed(SEQ_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofFixed(ICON_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofWeight(7, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(120),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.LEFT),
                TableLayout.ColumnSpec.ofWeight(4, TableLayout.ColumnSpec.Align.CENTER).floorWidth(48),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.CENTER),
        };
        TableLayoutBuilder builder = new TableLayoutBuilder(this.textRenderer, headers, specs, ROW_HEIGHT)
                .rowSeparator(0x22FFFFFF, 2);
        List<FailedWarnEntry> entries = this.activeTab == Tab.FAILED
                ? this.plan.getFailed() : this.plan.getWarn();
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                FailedWarnEntry entry = entries.get(i);
                if (entry == null || entry.getItem() == null) continue;
                this.appendFailedWarnRow(builder, entry, i);
            }
        }
        return builder.build();
    }

    private void appendPlanRow(TableLayoutBuilder builder, PlanEntry entry, int index) {
        ItemStack stack = this.stackOf(entry.getItemId());
        TableLayoutBuilder.RowBuilder row = builder.addRow();
        row.text(Text.literal(String.valueOf(index + 1)), COLOR_GRAY);
        row.item(stack);
        row.text(stack.getName(), 0xFFFFFFFF);
        row.cell(MultiLineTextCell.of(List.of(entry.getItemId()), COLOR_GRAY, 0.8f, 14, 10));
        row.cell(MultiLineTextCell.of(List.of(entry.getPosition() == null ? "" : entry.getPosition()),
                COLOR_GRAY, 0.8f, 14, 10));
        row.text(Text.literal(String.valueOf(entry.getCount())), COLOR_YELLOW);
        row.text(Text.literal(String.valueOf(entry.getRedundancy())), COLOR_LIGHT_BLUE);
        row.done();
    }

    private void appendFailedWarnRow(TableLayoutBuilder builder, FailedWarnEntry entry, int index) {
        ShoppingItem item = entry.getItem();
        ItemStack stack = this.stackOf(item.getId());
        TableLayoutBuilder.RowBuilder row = builder.addRow();
        row.text(Text.literal(String.valueOf(index + 1)), COLOR_GRAY);
        row.item(stack);
        row.text(stack.getName(), 0xFFFFFFFF);
        row.cell(MultiLineTextCell.of(List.of(item.getId()), COLOR_GRAY, 0.8f, 14, 10));
        row.text(Text.literal(String.valueOf(entry.getCount())), COLOR_YELLOW);
        row.text(Text.literal(String.valueOf(entry.getRedundancy())), COLOR_LIGHT_BLUE);
        row.done();
    }

    private ItemStack stackOf(String itemId) {
        if (itemId == null || itemId.isBlank()) return ItemStack.EMPTY;
        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null) return ItemStack.EMPTY;
        return new ItemStack(Registries.ITEM.get(identifier));
    }

    /* ---- 跳转与返回 ---- */

    private void editAsList() {
        this.client.send(() -> this.client.setScreen(
                new ShoppingListScreen(new ShoppingListSource(this.planSource.getList(), null), this)));
    }

    private void closeScreen() {
        this.client.setScreen(this.parent);
    }

    /* ---- 事件 ---- */

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.hitEditBtn(mouseX, mouseY)) {
                this.openMetaEdit();
                return true;
            }
            Tab tab = this.hitTab(mouseX, mouseY);
            if (tab != null) {
                if (this.activeTab != tab) {
                    this.activeTab = tab;
                    this.rebuildKeepScroll();
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /* ---- 渲染 ---- */

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (this.isErrorState()) return;
        this.renderTitleHeader(ctx);
        this.renderWidgets(ctx, mouseX, mouseY, delta);
    }

    /** 标题（2 倍放大居中）+ 金色细分隔线 + 页签行 + 标题右侧「编辑」按钮。 */
    private void renderTitleHeader(DrawContext graphics) {
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        graphics.fill(0, 24, this.width, 25, 0xFFFFAA00);
        this.renderTabRow(graphics);
        this.renderEditBtn(graphics);
    }

    private void renderTabRow(DrawContext graphics) {
        for (Tab tab : Tab.values()) {
            int[] r = this.tabRect(tab);
            String label = this.tabLabel(tab);
            int textW = this.textRenderer.getWidth(label);
            int color = tab == this.activeTab ? 0xFFFFFFFF : COLOR_GRAY;
            graphics.drawTextWithShadow(this.textRenderer, Text.literal(label),
                    r[0] + (r[2] - textW) / 2, r[1] + (r[3] - 9) / 2, color);
            if (tab == this.activeTab) {
                graphics.fill(r[0] + 2, r[1] + r[3] - 2, r[0] + r[2] - 2, r[1] + r[3], 0xFFFFAA00);
            }
        }
    }

    private void renderEditBtn(DrawContext graphics) {
        TitleEditButton.render(graphics, this.textRenderer, this.width, "qab.msg.plan_gui.edit");
    }
}
