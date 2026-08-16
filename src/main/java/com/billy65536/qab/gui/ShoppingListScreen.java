package com.billy65536.qab.gui;

import com.billy65536.qab.automatic.InventoryCapacityCalculator;
import com.billy65536.qab.planner.model.ShoppingItem;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.background.SimpleColorBackground;
import dev.lambdaurora.spruceui.border.SimpleBorder;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.lambdaurora.spruceui.widget.SpruceButtonWidget;
import dev.lambdaurora.spruceui.widget.container.SpruceEntryListWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
 * 购物清单查看/编辑界面（基于 SpruceUI）。
 *
 * <p>只依赖 {@link IListSource} 抽象数据源，不直接触碰 {@link com.billy65536.qab.planner.model.ShoppingList}
 * 与持久化细节；将来「计划查看/编辑」可传入其它 IListSource 实现复用本界面。</p>
 *
 * <p>布局：顶部标题 + 金色分隔线 + 表头，中部 SpruceEntryListWidget 滚动列表
 * （每行：序号 → 物品图标 → 名称/ID/详情 → 现有/需求/冗余数量 → 上移/下移/删除按钮），
 * 底部「返回」「保存」按钮。列宽按内容自动测量分配（名称列弹性）。</p>
 */
public class ShoppingListScreen extends SpruceScreen {
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

    /** 行内按钮数（上移 / 下移 / 删除；编辑改为点击「需求」列进入）。 */
    public static final int BTNS = 3;
    /** 列宽权重单位总数：名称+图标 7 : ID 3 : 详情 2 : 现有 5 : 需求 4 : 冗余 3。 */
    private static final int COL_UNITS = 24;

    /** 现有数量刷新间隔（tick）。 */
    private static final int HAVE_REFRESH_TICKS = 10;

    private final IListSource<ShoppingItem> source;
    private final Screen parent;
    private final Map<String, Integer> haveCache = new HashMap<>();
    private int haveTick;

    private ShoppingListWidget listWidget;
    private int rowWidth;
    /** 行内各列起点 x（相对行左缘）。 */
    private int nameX;
    private int idX;
    private int detX;
    private int haveX;
    private int needX;
    private int redunX;
    private int btnsX;
    /** 各数量列宽。 */
    private int haveW;
    private int needW;
    private int redunW;

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
        super.init();
        int listH = this.height - HEADER_Y - FOOTER_H;
        this.listWidget = new ShoppingListWidget(Position.of(0, HEADER_Y), this.width, Math.max(listH, 1), 0);
        this.listWidget.setBackground(new SimpleColorBackground(0xC0101010));
        this.listWidget.setBorder(new SimpleBorder(0xFF666666, 1));
        this.computeColumns();
        this.rebuildList();
        this.addDrawableChild(this.listWidget);

        this.addDrawableChild(new SpruceButtonWidget(
                Position.of(8, this.height - 24), 80, 20,
                Text.translatable("qab.msg.list_gui.back"), b -> this.closeScreen()));
        this.addDrawableChild(new SpruceButtonWidget(
                Position.of(this.width - 88, this.height - 24), 80, 20,
                Text.translatable("qab.msg.list_gui.save"), b -> this.save()));
    }

    /**
     * 计算行内列布局。
     *
     * <p>列宽先按权重（名称+图标 7 : ID 3 : 详情 2 : 现有 5 : 需求 4 : 冗余 3）分配，
     * 再以各列内容最大宽度为下限自动放大；总宽超出可用宽时优先回收富余、再按
     * 「详情→ID→名称」顺序压缩，最终富余空间全部归名称列（弹性列）。</p>
     */
    private void computeColumns() {
        this.rowWidth = this.listWidget.getInnerWidth();
        int avail = this.rowWidth - SEQ_W - (BTN_W * BTNS + BTN_GAP * (BTNS - 1));
        var font = this.textRenderer;

        // 文本列内容最大宽度（名称 9px / ID、详情 8px 缩放）
        int nameC = ICON_W + 8;
        int idC = 8;
        int detC = 8;
        List<ShoppingItem> items = this.source.getItems();
        if (items != null) {
            for (ShoppingItem item : items) {
                if (item == null) {
                    continue;
                }
                nameC = Math.max(nameC, ICON_W + font.getWidth(this.stackOf(item).getName()) + 8);
                String id = item.getId();
                if (id != null && !id.isBlank()) {
                    idC = Math.max(idC, (int) (font.getWidth(id) * 0.8f) + 8);
                }
                for (String detail : this.detailsOf(item)) {
                    detC = Math.max(detC, (int) (font.getWidth(detail) * 0.8f) + 8);
                }
            }
        }
        // 数量列（数字很短，按上限估算；需求列需容纳编辑框）
        int haveC = font.getWidth("9999") + 10;
        int needC = Math.max(font.getWidth("99999") + 10, 48);
        int redunC = font.getWidth("+99") + 10;

        // 权重基础宽与内容下限取大者
        float unit = Math.max(avail / (float) COL_UNITS, 1f);
        int[] w = {
                Math.max((int) (7 * unit), nameC),
                Math.max((int) (3 * unit), idC),
                Math.max((int) (2 * unit), detC),
                Math.max((int) (5 * unit), haveC),
                Math.max((int) (4 * unit), needC),
                Math.max((int) (3 * unit), redunC)
        };
        int[] content = {nameC, idC, detC, haveC, needC, redunC};

        // 总宽超出可用宽：先回收「富余」列，再按 详情→ID→名称→冗余→需求→现有 压缩到内容下限
        int over = w[0] + w[1] + w[2] + w[3] + w[4] + w[5] - avail;
        for (int i = 0; i < 6 && over > 0; i++) {
            int extra = w[i] - content[i];
            if (extra > 0) {
                int take = Math.min(extra, over);
                w[i] -= take;
                over -= take;
            }
        }
        int[] order = {2, 1, 0, 5, 4, 3};
        for (int c : order) {
            if (over <= 0) {
                break;
            }
            int floor = Math.max(content[c] / 2, 24);
            int reducible = Math.max(w[c] - floor, 0);
            int take = Math.min(reducible, over);
            w[c] -= take;
            over -= take;
        }
        // 富余空间归名称列（弹性）
        w[0] -= over;

        int iconName = w[0];
        this.nameX = SEQ_W + ICON_W;
        this.idX = SEQ_W + iconName;
        this.detX = this.idX + w[1];
        this.haveX = this.detX + w[2];
        this.needX = this.haveX + w[3];
        this.redunX = this.needX + w[4];
        this.btnsX = this.redunX + w[5];
        this.haveW = w[3];
        this.needW = w[4];
        this.redunW = w[5];
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

    /** 行详情文字（附魔 + NBT 要求），与 {@link ListRowEntry#buildDetails()} 同构。 */
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

    /** 重建列表行（行操作后调用，保持滚动位置）。 */
    private void rebuildList() {
        this.listWidget.clearRows();
        List<ShoppingItem> items = this.source.getItems();
        if (items == null) {
            return;
        }
        int index = 0;
        for (ShoppingItem item : items) {
            if (item == null) continue;
            this.listWidget.addEntry(new ListRowEntry(this, item, index));
            index++;
        }
    }

    /* ---- 行操作（由 ListRowEntry 调用） ---- */

    public void moveUp(int index) {
        this.source.moveUp(index);
        this.rebuildList();
    }

    public void moveDown(int index) {
        this.source.moveDown(index);
        this.rebuildList();
    }

    public void removeItem(int index) {
        this.source.remove(index);
        this.rebuildList();
    }

    /* ---- 现有数量（每 10 tick 刷新） ---- */

    public int getHaveCount(String itemId) {
        Integer count = this.haveCache.get(itemId);
        return count == null ? -1 : count;
    }

    private void refreshHaveCounts() {
        var player = this.client.player;
        if (player == null) return;
        List<ShoppingItem> items = this.source.getItems();
        if (items == null) return;
        this.haveCache.clear();
        for (ShoppingItem item : items) {
            if (item.getId() == null || item.getId().isBlank()) continue;
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

    private void closeScreen() {
        this.client.setScreen(this.parent);
    }

    /* ---- 渲染：标题 + 表头 ---- */

    @Override
    public void renderTitle(DrawContext graphics, int mouseX, int mouseY, float delta) {
        // 标题（18px ≈ 9px 放大 2 倍，居中，占 y=4~22）
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        // 金色细分隔线（标题正下方，y=24~25，与表头/列表分离）
        graphics.fill(0, 24, this.width, 25, 0xFFFFAA00);
        // 九栏表头（金色，对齐行内列）：序号/物品/名称/ID/详情/现有/需求/冗余/操作
        this.drawHeader(graphics, "qab.msg.list_gui.h_seq", SEQ_W / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_item", SEQ_W + ICON_W / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_name", this.nameX + (this.idX - this.nameX) / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_id", (this.idX + this.detX) / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_detail", (this.detX + this.haveX) / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_have", this.haveX + this.haveW / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_need", this.needX + this.needW / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_redun", this.redunX + this.redunW / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_action",
                this.btnsX + (BTN_W * BTNS + BTN_GAP * (BTNS - 1)) / 2);
    }

    private void drawHeader(DrawContext graphics, String key, int x) {
        graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(key), x, HEADER_Y - 9, 0xFFFFAA00);
    }

    /* ---- 列布局访问（供 ListRowEntry 使用） ---- */

    public int getRowWidth() {
        return this.rowWidth;
    }

    public int nameX() { return this.nameX; }

    public int idX() { return this.idX; }

    public int detX() { return this.detX; }

    public int haveX() { return this.haveX; }

    public int needX() { return this.needX; }

    public int redunX() { return this.redunX; }

    public int btnsX() { return this.btnsX; }

    public int haveW() { return this.haveW; }

    public int needW() { return this.needW; }

    public int redunW() { return this.redunW; }

    /** 清单级冗余数量（仅展示，不参与需求计算）。 */
    public int getRedundancy() {
        return this.source instanceof ShoppingListSource s ? s.getList().getRedundancy() : 0;
    }

    /** 内部列表控件：包装 protected 的增删入口，暴露为 public。 */
    private static class ShoppingListWidget extends SpruceEntryListWidget<ListRowEntry> {
        public ShoppingListWidget(Position position, int width, int height, int anchorYOffset) {
            super(position, width, height, anchorYOffset, ListRowEntry.class);
        }

        @Override
        public int addEntry(ListRowEntry entry) {
            return super.addEntry(entry);
        }

        /** 清空全部行（父类 clearEntries 为 protected final，无法覆写，故另名包装）。 */
        public void clearRows() {
            super.clearEntries();
        }
    }
}
