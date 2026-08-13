package com.billy65536.qab.gui;

import com.billy65536.qab.automatic.InventoryCapacityCalculator;
import com.billy65536.qab.planner.model.ShoppingItem;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.background.SimpleColorBackground;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.lambdaurora.spruceui.widget.SpruceButtonWidget;
import dev.lambdaurora.spruceui.widget.container.SpruceEntryListWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * 购物清单查看/编辑界面（基于 SpruceUI）。
 *
 * <p>只依赖 {@link IListSource} 抽象数据源，不直接触碰 {@link com.billy65536.qab.planner.model.ShoppingList}
 * 与持久化细节；将来「计划查看/编辑」可传入其它 IListSource 实现复用本界面。</p>
 *
 * <p>布局：顶部标题 + 金色分隔线 + 表头，中部 SpruceEntryListWidget 滚动列表
 * （每行：序号 → 物品图标 → 名称/ID/详情 → 现有/需求/冗余数量 → 上移/下移/编辑/删除按钮），
 * 底部「返回」「保存」按钮。</p>
 */
public class ShoppingListScreen extends SpruceScreen {
    /** 列表顶部（标题区）高度。 */
    public static final int HEADER_Y = 30;
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

    private static final int BTNS = 4;
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

    private void computeColumns() {
        this.rowWidth = this.listWidget.getInnerWidth();
        float unit = Math.max((this.rowWidth - SEQ_W - (BTN_W * BTNS + BTN_GAP * (BTNS - 1))) / (float) COL_UNITS, 1f);
        int iconName = (int) (7 * unit);
        int id = (int) (3 * unit);
        int det = (int) (2 * unit);
        int have = (int) (5 * unit);
        int need = (int) (4 * unit);
        int redun = (int) (3 * unit);
        this.nameX = SEQ_W + ICON_W;
        this.idX = this.nameX + (iconName - ICON_W);
        this.detX = this.idX + id;
        this.haveX = this.detX + det;
        this.needX = this.haveX + have;
        this.redunX = this.needX + need;
        this.btnsX = this.redunX + redun;
        this.haveW = have;
        this.needW = need;
        this.redunW = redun;
    }

    /** 重建列表行（行操作后调用，保持滚动位置）。 */
    private void rebuildList() {
        this.listWidget.clearRows();
        int index = 0;
        for (ShoppingItem item : this.source.getItems()) {
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
        this.haveCache.clear();
        for (ShoppingItem item : this.source.getItems()) {
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
        // 标题（18px ≈ 9px 放大 2 倍，居中）
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 4, 0xFFFFFFFF);
        matrices.pop();
        // 金色分隔线
        graphics.fill(0, 24, this.width, 25, 0xFFFFAA00);
        // 表头（金色，对齐行内列）
        this.drawHeader(graphics, "qab.msg.list_gui.h_seq", SEQ_W / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_item", this.nameX);
        this.drawHeader(graphics, "qab.msg.list_gui.h_id", this.idX);
        this.drawHeader(graphics, "qab.msg.list_gui.h_detail", this.detX);
        this.drawHeader(graphics, "qab.msg.list_gui.h_have", this.haveX + this.haveW / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_need", this.needX + this.needW / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_redun", this.redunX + this.redunW / 2);
        this.drawHeader(graphics, "qab.msg.list_gui.h_action", this.btnsX);
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
