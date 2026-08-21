package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件列表最小 Screen 壳。
 *
 * <p>仅负责标题渲染与承载 {@link FileListView}（列表组件本身无标题、可嵌入；
 * 未来主 GUI 页面可直接复用 FileListView 整合多个列表实例）。</p>
 */
public class FileListScreen extends ScreenContainer {

    /** 列表区顶部 y（2x 标题 + 金色分隔线之下）。 */
    public static final int HEADER_Y = 28;

    private final ListActions actions;
    private final List<FileEntry> entries;
    private final FileListView.Callbacks callbacks;
    /** 需要高亮的行索引（-1 无），compound 列表用于标记选中行。 */
    private final int highlightedRow;

    private FileListView view;

    public FileListScreen(Text title, ListActions actions, List<FileEntry> entries, FileListView.Callbacks callbacks) {
        this(title, actions, entries, callbacks, -1);
    }

    public FileListScreen(Text title, ListActions actions, List<FileEntry> entries,
                          FileListView.Callbacks callbacks, int highlightedRow) {
        super(title);
        this.actions = actions;
        this.entries = entries;
        this.callbacks = callbacks;
        this.highlightedRow = highlightedRow;
    }

    /** 列表组件（init 后可用）。 */
    public FileListView getView() {
        return this.view;
    }

    /** 刷新列表（保持滚动），供保存成功等场景回调使用。 */
    public void refresh(List<FileEntry> entries) {
        if (this.view != null) {
            this.view.setEntries(entries);
        }
    }

    /** 按路径即时刷新选中行高亮（compound 选择后调用），并滚动到可见区。 */
    public void highlight(Path path) {
        if (this.view != null) {
            this.view.setHighlightedRowByPath(path);
        }
    }

    @Override
    protected void init() {
        if (this.isErrorState()) {
            super.init();
            return;
        }
        this.view = new FileListView(this.textRenderer, this.actions, this.entries, this.callbacks);
        this.view.setHighlightedRow(this.highlightedRow);
        this.setLayout(this.view);
        super.init();
        this.view.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y);
        this.view.layout();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (this.isErrorState()) {
            return;
        }
        this.renderTitleHeader(ctx);
        this.renderWidgets(ctx, mouseX, mouseY, delta);
    }

    private void renderTitleHeader(DrawContext graphics) {
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        graphics.fill(0, 24, this.width, 25, 0xFFFFAA00);
    }
}
