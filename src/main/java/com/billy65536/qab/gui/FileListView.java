package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import com.billy65536.infrastructure.core.gui.layout.IContentCell;
import com.billy65536.infrastructure.core.gui.layout.TableLayout;
import com.billy65536.infrastructure.core.gui.layout.TableLayoutBuilder;
import com.billy65536.infrastructure.core.gui.layout.TextCell;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 通用文件列表组件（无标题、可嵌入）。
 *
 * <p>只包含「列表 + 操作按钮」部分，不含标题栏，便于未来主 GUI 页面
 * 在同一 Screen 内整合多个列表实例。内部由 {@link TableLayout} 承载
 * 列表（表头/滚动/悬停/编辑现成能力），操作按钮用 {@code PositionCell}
 * 青色文本渲染；交互策略：按钮命中优先消费，未命中且落在行区域时触发
 * 「行点击」兜底（有内页的类型 = 打开内页，无内页的 = 选择）。</p>
 *
 * <p>数据与命令层解耦：本组件只持有条目与回调（{@link Callbacks}），
 * 不引用 {@code QabCommands} 状态；保存组件（{@link SaveBarLayout}）
 * 按 {@link ListActions#withSaveBar()} 决定是否挂载。</p>
 */
public class FileListView extends AbstractLayout {

    /** 行高。 */
    public static final int ROW_HEIGHT = 26;
    /** 保存组件（SaveBarLayout）高度。 */
    public static final int SAVE_BAR_H = 34;
    /** 文件名列最小宽度。 */
    private static final int COL_FILE_MIN_W = 160;
    /** 按钮列宽度。 */
    private static final int COL_BTN_W = 56;

    /** 列表交互回调（由命令层/宿主 Screen 装配，GUI 层不持有命令层状态）。 */
    public interface Callbacks {
        /** 【打开】按钮 / 有内页时的行点击。 */
        void onOpen(FileEntry entry);

        /** 【选择】按钮 / 无内页时的行点击。 */
        void onSelect(FileEntry entry);

        /** 保存（compound 专用）。name 为输入的文件名；done(ok) 反馈结果，true 时自动收起并刷新。 */
        void onSave(String name, Consumer<Boolean> done);

        /** 保存输入框默认名（null 则留空）。 */
        @Nullable String defaultSaveName();
    }

    private final TextRenderer tr;
    private final ListActions actions;
    private final Callbacks callbacks;

    private List<FileEntry> entries = List.of();
    private TableLayout table;
    private SaveBarLayout saveBar;
    private int highlightedRow = -1;

    public FileListView(TextRenderer tr, ListActions actions, List<FileEntry> entries, Callbacks callbacks) {
        this.tr = tr;
        this.actions = actions;
        this.callbacks = callbacks;
        this.setEntries(entries);
    }

    /** 整体替换条目并重建表格，保持纵向滚动位置与高亮行；重建后重新排布（refresh 场景必须）。 */
    public void setEntries(List<FileEntry> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        this.rebuild();
        this.setHighlightedRow(this.highlightedRow);
        this.layout();
    }

    /** 设置高亮行索引（-1 清除），委托表格渲染金色背景。 */
    public void setHighlightedRow(int row) {
        this.highlightedRow = row;
        if (this.table != null) {
            this.table.setHighlightedRow(row);
        }
    }

    /** 保存组件（无保存按钮时返回 null）。 */
    @Nullable
    public SaveBarLayout getSaveBar() {
        return this.saveBar;
    }

    @Override
    public void layout() {
        int saveH = this.saveBar == null ? 0 : SAVE_BAR_H;
        this.table.setBounds(0, 0, this.width, this.height - saveH);
        this.table.reflow(this.width);
        this.table.setHighlightedRow(this.highlightedRow);
        if (this.saveBar != null) {
            this.saveBar.setBounds(0, this.height - saveH, this.width, saveH);
        }
    }

    private void rebuild() {
        int scroll = this.table == null ? 0 : this.table.getScrollOffset();
        if (this.children != null) {
            this.children.clear();
        }
        this.addChild(this.table = this.buildTable());
        this.table.setScrollOffset(scroll);
        if (this.actions.withSaveBar()) {
            this.addChild(this.saveBar = new SaveBarLayout(this.tr, this.callbacks));
        }
    }

    private TableLayout buildTable() {
        List<String> headers = new ArrayList<>();
        List<TableLayout.ColumnSpec> specs = new ArrayList<>();
        headers.add(Text.translatable("qab.msg.file_gui.h_file").getString());
        specs.add(TableLayout.ColumnSpec.ofWeight(1, TableLayout.ColumnSpec.Align.LEFT).elastic().floorWidth(COL_FILE_MIN_W));
        if (this.actions.withOpen()) {
            headers.add(Text.translatable("qab.msg.file_gui.open").getString());
            specs.add(TableLayout.ColumnSpec.ofFixed(COL_BTN_W, TableLayout.ColumnSpec.Align.CENTER));
        }
        if (this.actions.withSelect()) {
            headers.add(Text.translatable("qab.msg.file_gui.select").getString());
            specs.add(TableLayout.ColumnSpec.ofFixed(COL_BTN_W, TableLayout.ColumnSpec.Align.CENTER));
        }
        TableLayoutBuilder builder = new TableLayoutBuilder(
                this.tr,
                headers.toArray(new String[0]),
                specs.toArray(new TableLayout.ColumnSpec[0]),
                ROW_HEIGHT)
                .rowSeparator(0x22FFFFFF, 2);
        for (FileEntry entry : this.entries) {
            TableLayoutBuilder.RowBuilder row = builder.addRow();
            row.cell(this.nameCell(entry));
            if (this.actions.withOpen()) {
                row.position(Text.translatable("qab.msg.file_gui.open"), () -> this.callbacks.onOpen(entry));
            }
            if (this.actions.withSelect()) {
                row.position(Text.translatable("qab.msg.file_gui.select"), () -> this.callbacks.onSelect(entry));
            }
            row.done();
        }
        return builder.build();
    }

    private IContentCell nameCell(FileEntry entry) {
        if (entry.globalPath()) {
            // tooltip 由 renderTooltip() 统一绘制完整路径（本组件未接入 TableLayout.getCellTooltip 宿主通道）
            return TextCell.of(Text.literal(entry.displayName())
                    .formatted(Formatting.UNDERLINE, Formatting.AQUA));
        }
        return TextCell.of(Text.literal(entry.displayName())).withColor(0xFFFFFFFF);
    }

    /* ---- 事件 ---- */

    @Override
    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        // 点击保存组件外部（组件自身未消费）且其处于展开态 → 收起
        if (this.saveBar != null && this.saveBar.isExpanded()
                && !this.saveBar.isMouseOver(mouseX - this.saveBar.getX(), mouseY - this.saveBar.getY())) {
            this.saveBar.collapse();
        }
        // 行点击兜底：按钮/编辑格已被表格消费，剩余落在行区域的点击触发打开/选择
        if (mouseY < this.table.getY() || mouseY >= this.table.getY() + this.table.getHeight()) {
            return false;
        }
        int row = this.table.getRowAtY(mouseY);
        if (row >= 0 && row < this.entries.size()) {
            FileEntry entry = this.entries.get(row);
            if (this.actions.withOpen()) {
                this.callbacks.onOpen(entry);
            } else {
                this.callbacks.onSelect(entry);
            }
            return true;
        }
        return false;
    }

    /* ---- 渲染 ---- */

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        this.renderTooltip(ctx, mouseX, mouseY);
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (this.entries.isEmpty()) {
            String msg = Text.translatable("qab.msg.file_gui.empty").getString();
            ctx.drawCenteredTextWithShadow(this.tr, Text.literal(msg), this.width / 2, 44, 0xFFAAAAAA);
        }
    }

    /** 全局路径文件悬停时展示完整路径（屏幕坐标，render 阶段鼠标坐标即屏幕坐标）。 */
    private void renderTooltip(DrawContext ctx, int mouseX, int mouseY) {
        if (this.table == null) {
            return;
        }
        int row = this.table.getHoveredRow();
        if (row < 0 || row >= this.entries.size()) {
            return;
        }
        FileEntry entry = this.entries.get(row);
        if (!entry.globalPath()) {
            return;
        }
        ctx.drawTooltip(this.tr, Text.literal(entry.path().toString()), mouseX, mouseY);
    }
}
