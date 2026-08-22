package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.layout.DynamicTextCell;
import com.billy65536.infrastructure.core.gui.toast.Messenger;
import com.billy65536.infrastructure.core.gui.toast.ToastType;
import com.billy65536.infrastructure.core.gui.layout.MultiLineTextCell;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;
import com.billy65536.qab.QShopAutoBuyMod;
import com.billy65536.qab.QShopAutoBuyer;
import com.billy65536.qab.automatic.InventoryCapacityCalculator;
import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;

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
 * <p>顶部标题 + 标题右侧「编辑」按钮（进入 {@link MetaEditScreen} 改 name/desc）
 * + 金色分隔线 + 全局设置行（倍率 / 冗余率%，点击进入编辑）+ 表头，
 * 中部 TableLayout 滚动列表（拖拽手柄 → 图标 → 名称/ID/详情 → 现有/需求/冗余数量），
 * 底部「返回」；文件列表带参打开时额外显示「选择」（设为命令层选中状态后返回文件列表）、
 * 「另存为」「立即生成」「保存」按钮（临时内存清单禁用「保存」，
 * 另存为成功后刷新为可用）。
 * 列宽由 TableLayout#reflow 按内容测量与权重分配；
 * 「需求」列行内编辑由 TableLayout 编辑框托管，事件经 ScreenContainer 递归分发；
 * 「冗余」列按全局倍率/冗余率% 动态显示（y=0 → "+x"，y≠0 → "y+x"）；
 * 行首手柄列支持拖拽排序（拖动到列表外释放删除行）。</p>
 */
public class ShoppingListScreen extends ScreenContainer {
    /** 列表顶部（标题 + 全局设置行 + 表头区）高度。 */
    public static final int HEADER_Y = 56;
    /** 全局设置行顶部 y（标题分隔线下）。 */
    public static final int SETTINGS_ROW_Y = 28;
    /** 全局设置行高度。 */
    public static final int SETTINGS_ROW_H = 20;
    /** 两个设置格之间的间距。 */
    public static final int SETTINGS_CELL_GAP = 24;
    /** 设置格文字两侧留白（加大点击命中区域）。 */
    public static final int SETTINGS_CELL_PAD = 16;
    /** 全局设置行语言键。 */
    public static final String KEY_G_MULTIPLIER = "qab.msg.list_gui.g_multiplier";
    public static final String KEY_G_REDUNDANCY_PERCENT = "qab.msg.list_gui.g_redundancy_percent";
    /** 底部按钮区高度。 */
    public static final int FOOTER_H = 28;
    /** 行高。 */
    public static final int ROW_HEIGHT = 26;
    /** 序号（拖拽手柄）列宽。 */
    public static final int SEQ_W = 34;
    /** 图标列宽。 */
    public static final int ICON_W = 20;

    /** 现有数量刷新间隔（tick）。 */
    private static final int HAVE_REFRESH_TICKS = 10;
    /** 详情最多显示行数。 */
    private static final int DETAIL_MAX_LINES = 2;

    private static final int COLOR_GRAY = 0xFFAAAAAA;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_YELLOW = 0xFFFFFF55;
    private static final int COLOR_LIGHT_BLUE = 0xFFAACCFF;

    /** 八栏表头翻译键（序号/物品/名称/ID/详情/现有/需求/冗余）。 */
    private static final String[] HEADER_KEYS = {
            "qab.msg.list_gui.h_seq",
            "qab.msg.list_gui.h_item",
            "qab.msg.list_gui.h_name",
            "qab.msg.list_gui.h_id",
            "qab.msg.list_gui.h_detail",
            "qab.msg.list_gui.h_have",
            "qab.msg.list_gui.h_need",
            "qab.msg.list_gui.h_redun",
    };

    private final IListSource<ShoppingItem> source;
    private final Screen parent;
    /** 选择回调（文件列表带参打开内页时注入；点击【选择】设为命令层选中状态后返回文件列表）。 */
    @Nullable private final Runnable selectAction;
    private final Map<String, Integer> haveCache = new HashMap<>();
    private int haveTick;

    private TableLayout layout;

    /** 全局设置行正在编辑的字段（倍率 / 冗余率%），null 表示未在编辑。 */
    @Nullable private SpruceTextFieldWidget settingsEditor;
    @Nullable private SettingsField settingsEditorKind;

    /** 「保存」按钮引用（另存为成功后临时清单转为正式，需刷新可用状态）。 */
    private ButtonWidget saveButton;
    /** 「另存为」命名输入框，null 表示未在输入。 */
    @Nullable private SpruceTextFieldWidget saveAsEditor;

    /** 全局设置行可编辑字段。 */
    private enum SettingsField {
        MULTIPLIER,
        REDUNDANCY_PERCENT,
    }

    public ShoppingListScreen(IListSource<ShoppingItem> source, Screen parent) {
        this(source, parent, null);
    }

    public ShoppingListScreen(IListSource<ShoppingItem> source, Screen parent, @Nullable Runnable selectAction) {
        super(Text.literal(listTitle(source)));
        this.source = source;
        this.parent = parent;
        this.selectAction = selectAction;
    }

    private static String listTitle(IListSource<ShoppingItem> source) {
        String name = source.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return Text.translatable("qab.msg.list_gui.title").getString();
    }

    @Override
    protected void init() {
        if (this.isErrorState()) {
            // 错误隔离态：不重建业务布局，仅由 ScreenContainer 重排错误界面
            super.init();
            return;
        }
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.back"), b -> this.closeScreen())
                .dimensions(8, this.height - 24, 80, 20).build());
        // 文件列表带参打开内页时显示【选择】：设为命令层选中状态后返回
        if (this.selectAction != null) {
            this.addDrawableChild(ButtonWidget.builder(
                            Text.translatable("qab.msg.list_gui.select"), b -> this.onSelectClicked())
                    .dimensions(92, this.height - 24, 60, 20).build());
        }
        // 底部按钮右对齐：另存为 | 立即生成 | 保存
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.save_as"), b -> this.startSaveAs())
                .dimensions(this.width - 290, this.height - 24, 90, 20).build());
        ButtonWidget generateButton = this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.generate_now"), b -> this.generateNow())
                .dimensions(this.width - 194, this.height - 24, 90, 20).build());
        // 立即生成依赖底层 ShoppingList（generateAndSavePlan 入参），仅购物清单源可用
        generateButton.active = this.source instanceof ShoppingListSource;
        this.saveButton = this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("qab.msg.list_gui.save"), b -> this.save())
                .dimensions(this.width - 98, this.height - 24, 90, 20).build());
        // 临时内存清单（path 为 null）不可直接保存，只能另存为
        this.saveButton.active = this.source.isPersistable();

        this.layout = this.buildLayout();
        this.setLayout(this.layout);
        super.init();
        // ScreenContainer.init 将根节点铺满全屏；表格需让出标题/表头与底部按钮区，
        // 高度 = 屏幕高 - 顶部表头区 - 底部按钮区，避免行内容与按钮重叠。
        this.layout.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y - FOOTER_H);
        this.layout.reflow(this.width);
    }

    /* ---- 布局构建 ---- */

    /** 冗余数量（来自 ShoppingListSource 的清单配置，其它数据源返回 0）。 */
    private int getRedundancy() {
        return this.source instanceof ShoppingListSource s ? s.getList().getRedundancy() : 0;
    }

    /** 全局倍率（来自 ShoppingListSource 的清单配置，其它数据源返回 1）。 */
    private double getMultiplier() {
        return this.source instanceof ShoppingListSource s ? s.getList().getMultiplier() : 1.0;
    }

    /** 全局冗余率%（来自 ShoppingListSource 的清单配置，其它数据源返回 0）。 */
    private double getRedundancyPercent() {
        return this.source instanceof ShoppingListSource s ? s.getList().getRedundancyPercent() : 0.0;
    }

    /** 全局设置行是否可见（仅购物清单数据源展示倍率/冗余率%）。 */
    private boolean settingsRowVisible() {
        return this.source instanceof ShoppingListSource;
    }

    /** 重建表格（行操作/合法编辑提交后调用，保持滚动位置）。 */
    private void rebuildKeepScroll() {
        int scroll = this.layout.getScrollOffset();
        this.layout = this.buildLayout();
        this.layout.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y - FOOTER_H);
        this.layout.reflow(this.width);
        this.layout.setScrollOffset(scroll);
        this.setLayout(this.layout);
    }

    private TableLayout buildLayout() {
        List<ShoppingItem> items = this.source.getItems();
        int haveFallback = this.textRenderer.getWidth("9999") + 10;
        // 冗余列可能显示「y+x」，按最宽形态预留列宽避免渲染时抖动
        int redunFallback = this.textRenderer.getWidth("9999+9999") + 10;

        String[] headers = new String[HEADER_KEYS.length];
        for (int i = 0; i < HEADER_KEYS.length; i++) {
            headers[i] = Text.translatable(HEADER_KEYS[i]).getString();
        }
        TableLayout.ColumnSpec[] specs = {
                TableLayout.ColumnSpec.ofFixed(SEQ_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofFixed(ICON_W, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofWeight(7, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(120),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.LEFT),
                TableLayout.ColumnSpec.ofWeight(2, TableLayout.ColumnSpec.Align.LEFT),
                TableLayout.ColumnSpec.ofWeight(5, TableLayout.ColumnSpec.Align.CENTER),
                TableLayout.ColumnSpec.ofWeight(4, TableLayout.ColumnSpec.Align.CENTER).floorWidth(48),
                TableLayout.ColumnSpec.ofWeight(3, TableLayout.ColumnSpec.Align.CENTER),
        };

        TableLayoutBuilder builder = new TableLayoutBuilder(this.textRenderer, headers, specs, ROW_HEIGHT)
                .rowSeparator(0x22FFFFFF, 2)
                // 拖拽排序：行首手柄列拖拽移动，拖出列表释放删除行
                .dragHandle(0, this::moveItem, this::removeItem);

        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                ShoppingItem item = items.get(i);
                if (item == null) {
                    continue;
                }
                this.appendRow(builder, item, i, haveFallback, redunFallback);
            }
        }
        return builder.build();
    }

    /** 装配一行单元格（八列：序号/图标/名称/ID/详情/现有/需求/冗余）。 */
    private void appendRow(TableLayoutBuilder builder, ShoppingItem item, int index,
                           int haveFallback, int redunFallback) {
        ItemStack stack = this.stackOf(item);
        List<String> details = this.detailsOf(item);
        String id = item.getId();

        TableLayoutBuilder.RowBuilder row = builder.addRow();
        row.blank(); // 序号列：拖拽手柄字符由 TableLayout 按手柄列渲染
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
        // 冗余列动态显示：y（固定冗余）=0 → "+x"，否则 "y+x"（x 为比率冗余）
        row.cell(DynamicTextCell.of(() -> this.redundancyText(item),
                COLOR_LIGHT_BLUE, TableLayout.ColumnSpec.Align.CENTER, redunFallback));
        row.done();
    }

    /** 冗余列文本（按全局倍率/冗余率% 与固定冗余实时计算）。 */
    private String redundancyText(ShoppingItem item) {
        int fixed = this.getRedundancy();
        int ratio = this.ratioRedundancyOf(item);
        return fixed == 0 ? "+" + ratio : fixed + "+" + ratio;
    }

    /** 比率冗余 = round(需求 × 冗余率%)，需求 = round(数量 × 倍率)。 */
    private int ratioRedundancyOf(ShoppingItem item) {
        double percent = this.getRedundancyPercent();
        if (percent <= 0) {
            return 0;
        }
        int demand = (int) Math.round(item.getCount() * this.getMultiplier());
        return (int) Math.round(demand * percent / 100.0);
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

    /** 需求数量提交：非法输入重建表格恢复原值显示（此时已离开编辑态，重建安全），合法则只写回不重建。 */
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
            // 非法输入：editable 值已被编辑框覆盖，重建表格恢复模型原值显示
            this.rebuildKeepScroll();
            return;
        }
        item.setCount(value);
        // 合法提交：只更新模型不重建表格，编辑切换/滚动位置保持连续
    }

    /* ---- 全局设置行（倍率 / 冗余率% 点击编辑） ---- */

    /** 数字友好格式：整数值去掉小数尾（1.0 → "1"，1.5 → "1.5"）。 */
    private static String fmtDouble(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)
                && v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** 设置字段当前值（取自清单模型）。 */
    private double settingsValue(SettingsField kind) {
        return kind == SettingsField.MULTIPLIER ? this.getMultiplier() : this.getRedundancyPercent();
    }

    /** 设置字段标签文本（不含冒号，本地化）。 */
    private String settingsLabel(SettingsField kind) {
        return Text.translatable(kind == SettingsField.MULTIPLIER
                ? KEY_G_MULTIPLIER : KEY_G_REDUNDANCY_PERCENT).getString();
    }

    /** 设置格矩形（屏幕坐标）：{x, y, w, h}，按「标签: 数字」整体宽度居中计算。 */
    private int[] settingsCellRect(SettingsField kind) {
        int textW = this.textRenderer.getWidth(this.settingsLabel(kind) + ": "
                + fmtDouble(this.settingsValue(kind)));
        int cellW = textW + SETTINGS_CELL_PAD;
        int totalW = cellW * 2 + SETTINGS_CELL_GAP;
        int startX = (this.width - totalW) / 2;
        int x = kind == SettingsField.MULTIPLIER ? startX : startX + cellW + SETTINGS_CELL_GAP;
        return new int[]{x, SETTINGS_ROW_Y, cellW, SETTINGS_ROW_H};
    }

    /** 「标签: 数字」整体居中后的起始 x（整格内）。 */
    private int settingsTextStartX(SettingsField kind) {
        int[] r = this.settingsCellRect(kind);
        String label = this.settingsLabel(kind) + ": ";
        String value = fmtDouble(this.settingsValue(kind));
        int textW = this.textRenderer.getWidth(label) + this.textRenderer.getWidth(value);
        return r[0] + (r[2] - textW) / 2;
    }

    /** 设置格内标签子矩形（屏幕坐标）：{x, y, w, h}，含「: 」（编辑时恒显示）。 */
    private int[] settingsLabelRect(SettingsField kind) {
        int[] r = this.settingsCellRect(kind);
        String label = this.settingsLabel(kind) + ": ";
        return new int[]{this.settingsTextStartX(kind), r[1], this.textRenderer.getWidth(label), r[3]};
    }

    /** 设置格内数字子矩形（屏幕坐标）：{x, y, w, h}，编辑框只覆盖此区域。 */
    private int[] settingsValueRect(SettingsField kind) {
        int[] lr = this.settingsLabelRect(kind);
        String value = fmtDouble(this.settingsValue(kind));
        return new int[]{lr[0] + lr[2], lr[1], this.textRenderer.getWidth(value), lr[3]};
    }

    /** 命中检测：返回鼠标所在设置字段，未命中或设置行不可见返回 null。 */
    @Nullable
    private SettingsField hitSettingsCell(double mouseX, double mouseY) {
        if (!this.settingsRowVisible()) {
            return null;
        }
        if (mouseY < SETTINGS_ROW_Y || mouseY >= SETTINGS_ROW_Y + SETTINGS_ROW_H) {
            return null;
        }
        for (SettingsField kind : SettingsField.values()) {
            int[] r = this.settingsCellRect(kind);
            if (mouseX >= r[0] && mouseX < r[0] + r[2]) {
                return kind;
            }
        }
        return null;
    }

    /** 打开对应设置字段的编辑框（仅覆盖数字子区域，标签保持显示）。 */
    private void startSettingsEditor(SettingsField kind) {
        if (this.settingsEditor != null) {
            this.removeSettingsEditor();
        }
        int[] r = this.settingsValueRect(kind);
        // 编辑框比数字略宽（左右各留白），避免输入时文字顶格
        SpruceTextFieldWidget field = new SpruceTextFieldWidget(
                Position.of(r[0] - 4, r[1]), r[2] + 8, r[3], Text.empty());
        field.setText(fmtDouble(this.settingsValue(kind)));
        this.addDrawableChild(field);
        // SpruceScreen.setFocused 同时设置屏幕级聚焦与 widget 级聚焦，
        // 键盘/字符事件经 Screen 分发到 focused child 到达编辑框
        this.setFocused(field);
        this.settingsEditor = field;
        this.settingsEditorKind = kind;
    }

    /** 提交当前设置编辑：解析 double，非法或 <0 放弃（文本格回落模型值），合法则写回。 */
    private void commitSettingsEditor() {
        if (this.settingsEditor == null || this.settingsEditorKind == null) {
            return;
        }
        String text = this.settingsEditor.getText();
        double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            value = Double.NaN;
        }
        if (Double.isFinite(value) && value >= 0) {
            if (this.settingsEditorKind == SettingsField.MULTIPLIER) {
                ((ShoppingListSource) this.source).getList().setMultiplier(value);
            } else {
                ((ShoppingListSource) this.source).getList().setRedundancyPercent(value);
            }
        }
        this.removeSettingsEditor();
    }

    /** 取消当前设置编辑（Esc，不写回）。 */
    private void cancelSettingsEditor() {
        this.removeSettingsEditor();
    }

    /** 卸载编辑框并释放聚焦。 */
    private void removeSettingsEditor() {
        if (this.settingsEditor == null) {
            return;
        }
        this.setFocused(null);
        this.remove(this.settingsEditor);
        this.settingsEditor = null;
        this.settingsEditorKind = null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (this.hitEditBtn(mouseX, mouseY)) {
            this.openMetaEdit();
            return true;
        }
        if (this.saveAsEditor != null) {
            int sx = this.saveAsEditor.getX();
            int sy = this.saveAsEditor.getY();
            int sw = this.saveAsEditor.getWidth();
            if (mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + 20) {
                // 点击输入框内：交给编辑框处理（光标定位），不提交
                return super.mouseClicked(mouseX, mouseY, button);
            }
            // 点击其它区域：取消另存为（避免误提交写盘）
            this.cancelSaveAs();
        }
        if (this.settingsEditor != null) {
            int[] r = this.settingsCellRect(this.settingsEditorKind);
            if (mouseX >= r[0] && mouseX < r[0] + r[2]
                    && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                // 点击编辑框内：交给编辑框处理（光标定位），不提交
                return super.mouseClicked(mouseX, mouseY, button);
            }
            // 点击其它区域：先提交当前编辑（与表格行内编辑行为一致）
            this.commitSettingsEditor();
        }
        SettingsField hit = this.hitSettingsCell(mouseX, mouseY);
        if (hit != null) {
            this.startSettingsEditor(hit);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.saveAsEditor != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.commitSaveAs();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.cancelSaveAs();
                return true;
            }
        }
        if (this.settingsEditor != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.commitSettingsEditor();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.cancelSettingsEditor();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /* ---- 行操作（拖拽回调） ---- */

    /** 拖拽排序：将 from 行移动到 to 位置后重建表格（保持滚动位置）。 */
    public void moveItem(int from, int to) {
        this.layout.cancelEdit();
        this.source.move(from, to);
        this.rebuildKeepScroll();
    }

    /** 拖出列表删除行。 */
    public void removeItem(int index) {
        this.layout.cancelEdit();
        this.source.remove(index);
        this.rebuildKeepScroll();
    }

    /* ---- 现有数量（每 10 tick 刷新） ---- */

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
        if (this.isErrorState()) {
            return; // 错误隔离态：停止业务刷新
        }
        if (++this.haveTick % HAVE_REFRESH_TICKS == 0) {
            this.refreshHaveCounts();
        }
    }

    /* ---- 标题「编辑」按钮 ---- */

    /** 打开统一 name/desc 编辑页（MetaEditScreen），返回后回本屏。 */
    private void openMetaEdit() {
        this.client.send(() -> this.client.setScreen(new MetaEditScreen(this.source, this)));
    }

    private int[] editBtnRect() {
        return TitleEditButton.rect(this.textRenderer, this.width, "qab.msg.list_gui.edit");
    }

    private boolean hitEditBtn(double mouseX, double mouseY) {
        return TitleEditButton.hit(mouseX, mouseY, this.editBtnRect());
    }

    /* ---- 另存为 ---- */

    /** 打开「另存为」命名输入框（预填当前清单名，Enter 提交 / Esc 或点击外部取消）。 */
    private void startSaveAs() {
        if (this.saveAsEditor != null) {
            return;
        }
        // 另存为聚焦输入框前，先提交未完成的设置行编辑，避免焦点冲突
        if (this.settingsEditor != null) {
            this.commitSettingsEditor();
        }
        int w = Math.min(this.width - 200, 240);
        int x = (this.width - w) / 2;
        SpruceTextFieldWidget field = new SpruceTextFieldWidget(
                Position.of(x, this.height - 56), w, 20,
                Text.translatable("qab.msg.list_gui.save_as_prompt"));
        String name = this.source.getName();
        field.setText(name == null ? "" : name);
        this.addDrawableChild(field);
        this.setFocused(field);
        this.saveAsEditor = field;
    }

    /** 提交另存为：写入正式目录（临时清单转为正式，保存按钮重新可用）。 */
    private void commitSaveAs() {
        if (this.saveAsEditor == null) {
            return;
        }
        String name = this.saveAsEditor.getText().trim();
        this.removeSaveAsEditor();
        boolean ok = this.source.saveAs(name);
        if (ok) {
            Messenger.notify(Text.translatable("qab.msg.list_gui.save_success"), ToastType.SUCCESS);
        } else {
            Messenger.error(Text.translatable("qab.msg.list_gui.save_failed"));
        }
        if (ok) {
            // 另存成功后源已指向正式文件，保存按钮恢复可用
            this.saveButton.active = this.source.isPersistable();
        }
    }

    /** 取消另存为（不写盘）。 */
    private void cancelSaveAs() {
        this.removeSaveAsEditor();
    }

    /** 卸载另存为输入框并释放聚焦。 */
    private void removeSaveAsEditor() {
        if (this.saveAsEditor == null) {
            return;
        }
        this.setFocused(null);
        this.remove(this.saveAsEditor);
        this.saveAsEditor = null;
    }

    /* ---- 立即生成 ---- */

    /** 用当前清单立即生成计划：调命令层公共生成核心，成功后切到 PlanScreen 展示。 */
    private void generateNow() {
        if (!(this.source instanceof ShoppingListSource s)) {
            return;
        }
        ShoppingList list = s.getList();
        String planName = list.getName();
        if (planName == null || planName.isBlank()) {
            planName = "plan"; // 清单未命名时的兜底计划名
        }
        final String finalName = planName;
        QShopAutoBuyer.GenerateResult result = QShopAutoBuyMod.BUYER.generateAndSavePlan(list, finalName);
        if (result.ok()) {
            Messenger.notify(Text.translatable(
                    "qab.msg.list_gui.generate_success", result.path().getFileName().toString()), ToastType.SUCCESS);
        } else {
            Messenger.error(Text.translatable(
                    result.errorKey() == null ? "qab.msg.list_gui.generate_failed" : result.errorKey(),
                    result.errorArgs()));
        }
        if (result.ok() && result.plan() != null) {
            // 成功后关闭当前 GUI，打开 PlanScreen 展示新计划（父屏幕为当前清单的返回目标）
            this.client.send(() -> this.client.setScreen(new PlanScreen(result.plan(), finalName, this.parent)));
        }
    }

    /* ---- 保存 ---- */

    private void save() {
        boolean ok = this.source.save();
        if (ok) {
            Messenger.notify(Text.translatable("qab.msg.list_gui.save_success"), ToastType.SUCCESS);
        } else {
            Messenger.error(Text.translatable("qab.msg.list_gui.save_failed"));
        }
        if (ok) {
            this.closeScreen();
        }
    }

    /** 返回父屏幕（构造时传入）。 */
    private void closeScreen() {
        this.client.setScreen(this.parent);
    }

    /** 【选择】按钮：执行注入的选择回调并返回父屏幕（文件列表）。 */
    private void onSelectClicked() {
        if (this.selectAction != null) {
            this.selectAction.run();
        }
        this.closeScreen();
    }

    /* ---- 渲染 ---- */

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta); // 背景 + 表格 + 布局 tooltip
        if (this.isErrorState()) {
            return; // 错误隔离态：super.render 已渲染全屏错误详情，不再画业务标题与按钮
        }
        this.renderTitleHeader(ctx);
        this.renderWidgets(ctx, mouseX, mouseY, delta); // 底部按钮
    }

    /** 标题（2 倍放大居中）+ 金色细分隔线 + 全局设置行（倍率 / 冗余率%）+ 右上角编辑按钮。 */
    private void renderTitleHeader(DrawContext graphics) {
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        graphics.fill(0, 24, this.width, 25, 0xFFFFAA00);
        this.renderEditBtn(graphics);
        if (this.settingsRowVisible()) {
            this.renderSettingsRow(graphics);
        }
    }

    /** 绘制标题右侧「编辑」按钮（半透明底 + 文字居中）。 */
    private void renderEditBtn(DrawContext graphics) {
        TitleEditButton.render(graphics, this.textRenderer, this.width, "qab.msg.list_gui.edit");
    }

    /** 绘制全局设置行：标签（含冒号）恒显示；数字未编辑时绘制，编辑时由编辑框接管。 */
    private void renderSettingsRow(DrawContext graphics) {
        for (SettingsField kind : SettingsField.values()) {
            int[] lr = this.settingsLabelRect(kind);
            graphics.drawTextWithShadow(this.textRenderer, Text.literal(this.settingsLabel(kind) + ": "),
                    lr[0], lr[1] + (lr[3] - 8) / 2, 0xFFFFFFFF);
            if (this.settingsEditorKind == kind && this.settingsEditor != null) {
                continue; // 数字由编辑框接管，不重复绘制
            }
            int[] vr = this.settingsValueRect(kind);
            graphics.drawTextWithShadow(this.textRenderer, Text.literal(fmtDouble(this.settingsValue(kind))),
                    vr[0], vr[1] + (vr[3] - 8) / 2, 0xFFFFFFFF);
        }
    }
}
