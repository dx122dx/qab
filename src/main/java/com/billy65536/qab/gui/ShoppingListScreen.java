package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.layout.DynamicTextCell;
import com.billy65536.infrastructure.core.gui.layout.MultiLineTextCell;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;
import com.billy65536.qab.automatic.InventoryCapacityCalculator;
import com.billy65536.qab.planner.model.ShoppingItem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物清单查看/编辑界面（infrastructure 布局框架 ScreenContainer + TableLayout）。
 *
 * <p>只依赖 {@link IListSource} 抽象数据源，不直接触碰
 * {@link com.billy65536.qab.planner.model.ShoppingList} 与持久化细节；将来
 * 「计划查看/编辑」可传入其它 IListSource 实现复用本界面。</p>
 *
 * <p>顶部标题 + 金色分隔线 + 表头，中部 TableLayout 滚动列表
 * （序号 → 图标 → 名称/ID/详情 → 现有/需求/冗余数量 → 上移/下移/删除），
 * 底部「返回」「保存」按钮。列宽由 TableLayout#reflow 按内容测量与权重分配；
 * 「需求」列行内编辑由 TableLayout 编辑框托管，事件经 ScreenContainer 递归分发。</p>
 */
public class ShoppingListScreen extends ScreenContainer {
    /** 列表顶部（标题 + 表头区）高度。 */
    public static final int HEADER_Y = 36;
    /** 底部按钮区高度。 */
    public static final int FOOTER_H = 28;
    /** 行高。 */
    public static final int ROW_HEIGHT = 26;
    /** 序号列宽。 */
    public static final int SEQ_W = 22;
    /** 图标列宽。 */
    public static final int ICON_W = 20;
    /** 行按钮宽。 */
    public static final int BTN_W = 18;
    /** 行按钮间距。 */
    public static final int BTN_GAP = 2;
    /** 行内按钮数（上移 / 下移 / 删除）。 */
    public static final int BTNS = 3;

    /** 现有数量刷新间隔（tick）。 */
    private static final int HAVE_REFRESH_TICKS = 10;
    /** 详情最多显示行数。 */
    private static final int DETAIL_MAX_LINES = 2;

    private static final int COLOR_GRAY = 0xFFAAAAAA;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_YELLOW = 0xFFFFFF55;
    private static final int COLOR_LIGHT_BLUE = 0xFFAACCFF;
    private static final int COLOR_DELETE_HOVER = 0x99FF5555;

    /** 九栏表头翻译键（序号/物品/名称/ID/详情/现有/需求/冗余/操作）。 */
    private static final String[] HEADER_KEYS = {
            "qab.msg.list_gui.h_seq",
            "qab.msg.list_gui.h_item",
            "qab.msg.list_gui.h_name",
            "qab.msg.list_gui.h_id",
            "qab.msg.list_gui.h_detail",
            "qab.msg.list_gui.h_have",
            "qab.msg.list_gui.h_need",
            "qab.msg.list_gui.h_redun",
            "qab.msg.list_gui.h_action",
    };

    private final IListSource<ShoppingItem> source;
    private final Screen parent;
    private final Map<String, Integer> haveCache = new HashMap<>();
    private int haveTick;

    private TableLayout layout;

    public ShoppingListScreen(IListSource<ShoppingItem> source, Screen parent) {
        super(Text.literal(listTitle(source)));
        this.source = source;
        this.parent = parent;
    }

    private static String listTitle(IListSource<ShoppingItem> source) {
        if (source instanceof ShoppingListSource s) {
            String name = s.getList().getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return Text.translatable("qab.msg.list_gui.title").getString();
    }

    @Override
    protected void init() {
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.back"), b -> this.closeScreen())
                .dimensions(8, this.height - 24, 80, 20).build());
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.save"), b -> this.save())
                .dimensions(this.width - 88, this.height - 24, 80, 20).build());

        this.layout = this.buildLayout();
        this.setLayout(this.layout);
        super.init();
        // ScreenContainer.init 将根节点铺满全屏；表格需让出标题/表头与底部按钮区
        this.layout.setBounds(0, HEADER_Y, this.width, this.height - FOOTER_H);
        this.layout.reflow(this.width);
    }

    /* ---- 布局构建 ---- */

    /** 冗余数量（来自 ShoppingListSource 的清单配置，其它数据源返回 0）。 */
    private int getRedundancy() {
        return this.source instanceof ShoppingListSource s ? s.getList().getRedundancy() : 0;
    }

    /** 重建表格（行操作/合法编辑提交后调用，保持滚动位置）。 */
    private void rebuildKeepScroll() {
        int scroll = this.layout.getScrollOffset();
        this.layout = this.buildLayout();
        this.layout.setBounds(0, HEADER_Y, this.width, this.height - FOOTER_H);
        this.layout.reflow(this.width);
        this.layout.setScrollOffset(scroll);
        this.setLayout(this.layout);
    }

    private TableLayout buildLayout() {
        List<ShoppingItem> items = this.source.getItems();
        int redundancy = this.getRedundancy();
        int haveFallback = this.textRenderer.getWidth("9999") + 10;

        String[] headers = new String[HEADER_KEYS.length];
        for (int i = 0; i < HEADER_KEYS.length; i++) {
            headers[i] = Text.translatable(HEADER_KEYS[i]).getString();
        }
        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofFixed(SEQ_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofFixed(ICON_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofWeight(7, TableLayout.ColumnSpec.Align.LEFT).elastic().shrinkPriority(2),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.LEFT).shrinkPriority(1),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT).shrinkPriority(0),
                TableLayout.ColumnSpec.ofWeight(5, TableLayout.ColumnSpec.Align.CENTER).shrinkPriority(5),
                TableLayout.ColumnSpec.ofWeight(4, TableLayout.ColumnSpec.Align.CENTER).shrinkPriority(4).floorWidth(48),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.CENTER).shrinkPriority(3),
                TableLayout.ColumnSpec.ofFixed(BTN_W * BTNS + BTN_GAP * (BTNS - 1), TableLayout.ColumnSpec.Align.CENTER),
        };

        TableLayoutBuilder builder = new TableLayoutBuilder(this.textRenderer, headers, specs, ROW_HEIGHT)
                .rowSeparator(0x22FFFFFF, 2);

        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                ShoppingItem item = items.get(i);
                if (item == null) {
                    continue;
                }
                this.appendRow(builder, item, i, redundancy, haveFallback);
            }
        }
        return builder.build();
    }

    /** 装配一行单元格（九列：序号/图标/名称/ID/详情/现有/需求/冗余/操作）。 */
    private void appendRow(TableLayoutBuilder builder, ShoppingItem item, int index,
                           int redundancy, int haveFallback) {
        ItemStack stack = this.stackOf(item);
        List<String> details = this.detailsOf(item);
        String id = item.getId();

        TableLayoutBuilder.RowBuilder row = builder.addRow();
        row.text(Text.literal(String.valueOf(index + 1)), COLOR_GRAY);
        row.item(stack);
        row.text(stack.getName(), 0xFFFFFFFF);
        if (id == null || id.isBlank()) {
            row.blank();
        } else {
            row.cell(MultiLineTextCell.of(List.of(id), COLOR_GRAY, 0.8f, 14, 10));
        }
        row.cell(MultiLineTextCell.of(details, COLOR_YELLOW, 0.8f, 14, DETAIL_MAX_LINES));
        row.cell(DynamicTextCell.of(() -> this.haveText(item.getId()),
                COLOR_GREEN, TableLayout.ColumnSpec.Align.CENTER, haveFallback));
        row.text(Text.literal(String.valueOf(item.getCount())), COLOR_YELLOW)
                .editable(String.valueOf(item.getCount()), v -> this.commitCount(item, v));
        row.text(Text.literal("+" + redundancy), COLOR_LIGHT_BLUE);
        row.blank(); // 操作列占位
        row.button(Text.literal("↑"), () -> this.moveUp(index));
        row.button(Text.literal("↓"), () -> this.moveDown(index));
        row.button(Text.literal("×"), () -> this.removeItem(index), COLOR_DELETE_HOVER);
        row.done();
    }

    /** 构造物品 ItemStack（非法 ID 返回 EMPTY）。 */
    private ItemStack stackOf(ShoppingItem item) {
        String id = item.getId();
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(Registries.ITEM.get(identifier));
    }

    /** 行详情文字（附魔 + NBT 要求）。 */
    private List<String> detailsOf(ShoppingItem item) {
        List<String> out = new ArrayList<>(4);
        var enchants = item.getEnchant();
        if (enchants != null) {
            for (var entry : enchants.entrySet()) {
                Identifier eid = Identifier.tryParse(entry.getKey());
                Enchantment enchantment = eid == null ? null : Registries.ENCHANTMENT.get(eid);
                if (enchantment != null) {
                    out.add(enchantment.getName(entry.getValue()).getString());
                } else {
                    out.add(entry.getKey());
                }
            }
        }
        String nbt = item.getMatchNbt();
        if (nbt != null && !nbt.isBlank()) {
            out.add("NBT: " + nbt);
        }
        return out;
    }

    /* ---- 行内编辑（点击「需求」列进入） ---- */

    /** 需求数量提交：非法输入忽略（表格重建后自动恢复原值显示），合法则写回并刷新显示。 */
    private void commitCount(ShoppingItem item, String text) {
        String t = text == null ? "" : text.trim();
        int value = -1;
        if (!t.isEmpty()) {
            try {
                value = Integer.parseInt(t);
            } catch (NumberFormatException ignored) {
                value = -1;
            }
        }
        if (value < 0) {
            return;
        }
        item.setCount(value);
        this.rebuildKeepScroll();
    }

    /* ---- 行操作 ---- */

    public void moveUp(int index) {
        this.layout.cancelEdit();
        this.source.moveUp(index);
        this.rebuildKeepScroll();
    }

    public void moveDown(int index) {
        this.layout.cancelEdit();
        this.source.moveDown(index);
        this.rebuildKeepScroll();
    }

    public void removeItem(int index) {
        this.layout.cancelEdit();
        this.source.remove(index);
        this.rebuildKeepScroll();
    }

    /* ---- 现有数量（每 10 tick 刷新） ---- */

    public int getHaveCount(String itemId) {
        Integer count = this.haveCache.get(itemId);
        return count == null ? -1 : count;
    }

    private String haveText(String itemId) {
        int have = this.haveCache.getOrDefault(itemId, -1);
        return have < 0 ? "?" : String.valueOf(have);
    }

    private void refreshHaveCounts() {
        var player = this.client.player;
        if (player == null) {
            return;
        }
        List<ShoppingItem> items = this.source.getItems();
        if (items == null) {
            return;
        }
        this.haveCache.clear();
        for (ShoppingItem item : items) {
            if (item.getId() == null || item.getId().isBlank()) {
                continue;
            }
            this.haveCache.put(item.getId(), InventoryCapacityCalculator.countItems(player, item.getId()));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++this.haveTick % HAVE_REFRESH_TICKS == 0) {
            this.refreshHaveCounts();
        }
    }

    /* ---- 保存 ---- */

    private void save() {
        boolean ok = this.source.save();
        if (this.client.player != null) {
            this.client.player.sendMessage(
                    Text.translatable(ok ? "qab.msg.list_gui.save_success" : "qab.msg.list_gui.save_failed"), false);
        }
        if (ok) {
            this.closeScreen();
        }
    }

    /** 返回父屏幕（构造时传入）。 */
    private void closeScreen() {
        this.client.setScreen(this.parent);
    }

    /* ---- 渲染 ---- */

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta); // 背景 + 表格 + 布局 tooltip
        this.renderTitleHeader(ctx);
        this.renderWidgets(ctx, mouseX, mouseY, delta); // 底部按钮
    }

    /** 标题（2 倍放大居中）+ 金色细分隔线。 */
    private void renderTitleHeader(DrawContext graphics) {
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        graphics.fill(0, 24, this.width, 25, 0xFFFFAA00);
    }
}
