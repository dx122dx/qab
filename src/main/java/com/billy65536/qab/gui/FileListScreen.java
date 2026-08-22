package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件列表最小 Screen 壳。
 *
 * <p>仅负责标题渲染与承载 {@link RootLayout}（可选上下工具条 + {@link FileListView}）。
 * 标题按类型由调用方传入本地化键（如 {@code qab.msg.file_gui.title_list}）；工具条
 * 不特化组件，由调用方用现有布局组件组装后经 topBar / bottomBar 槽位注入。</p>
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

    /** 可选上方工具条（标题分隔线之下），null 不挂载。 */
    @Nullable
    private final AbstractLayout topBar;

    /** 可选下方工具条（列表底部），null 不挂载。 */
    @Nullable
    private final AbstractLayout bottomBar;

    public FileListScreen(Text title, ListActions actions, List<FileEntry> entries, FileListView.Callbacks callbacks) {
        this(title, actions, entries, callbacks, -1);
    }

    public FileListScreen(Text title, ListActions actions, List<FileEntry> entries,
                          FileListView.Callbacks callbacks, int highlightedRow) {
        this(title, actions, entries, callbacks, highlightedRow, null, null);
    }

    public FileListScreen(Text title, ListActions actions, List<FileEntry> entries,
                          FileListView.Callbacks callbacks, int highlightedRow,
                          @Nullable AbstractLayout topBar, @Nullable AbstractLayout bottomBar) {
        super(title);
        this.actions = actions;
        this.entries = entries;
        this.callbacks = callbacks;
        this.highlightedRow = highlightedRow;
        this.topBar = topBar;
        this.bottomBar = bottomBar;
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
        RootLayout root = new RootLayout(this.view, this.topBar, this.bottomBar);
        this.setLayout(root);
        super.init();
        root.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y);
        root.layout();
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
