package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import com.billy65536.qab.QShopAutoBuyMod;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 文件列表根容器：按 {@link FileListType} 组合「顶部工具条 + {@link FileListView}」的完整布局。
 *
 * <p>工具条内容统一由 {@link FileListToolbarFactory} 产出（不在此处或调用方散落组装）；
 * 列表由本布局内部创建并持有（{@link #getView()} 转发）；高亮铁律在此统一落实：
 * 任何 {@code onSelect} 先执行业务回调（更新 {@code QShopAutoBuyer} 选中状态），
 * 再从 {@code BUYER.highlightPathFor(type)} 取路径调用
 * {@link FileListView#setHighlightedRowByPath} —— 独立屏与仪表盘内嵌共用，杜绝本地猜测。</p>
 *
 * <p>compound 底部保存条由 {@link FileListView}（{@code withSaveBar}）自行挂载在列表底部，
 * 本布局不再额外管理 bottomBar（保留可选槽位供未来扩展）。</p>
 */
public class RootLayout extends AbstractLayout {

    /** 工具条槽位高度（与 {@link FileListView#SAVE_BAR_H} 对齐）。 */
    public static final int TOOLBAR_H = 34;

    private final FileListView view;
    @Nullable
    private final AbstractLayout topBar;
    @Nullable
    private final AbstractLayout bottomBar;

    /**
     * @param tr             字体渲染器
     * @param type           文件列表类型（决定工具条内容与高亮来源）
     * @param actions        行操作按钮集
     * @param entries        初始条目
     * @param callbacks      业务回调（onSelect 内只需更新 BUYER 状态与反馈，高亮由本布局包装）
     * @param host           宿主（独立屏跳转 / 仪表盘内嵌切换）
     * @param highlightedRow 初始高亮行（-1 无）
     */
    public RootLayout(TextRenderer tr, FileListType type, ListActions actions,
                      List<FileEntry> entries, FileListView.Callbacks callbacks,
                      FileListHost host, int highlightedRow) {
        this(tr, type, actions, entries, callbacks, host, highlightedRow, null);
    }

    /**
     * 带可选底部工具条的完整构造（compound 底部保存条由 {@link FileListView} 自行管理，
     * 此槽位保留给未来扩展，当前调用方传 null）。
     */
    public RootLayout(TextRenderer tr, FileListType type, ListActions actions,
                      List<FileEntry> entries, FileListView.Callbacks callbacks,
                      FileListHost host, int highlightedRow,
                      @Nullable AbstractLayout bottomBar) {
        // 列表与工具条 SaveBar 共用同一份包装回调（onSave 成功后统一刷新/切回 LIST）
        FileListView.Callbacks wrapped = wrapCallbacks(type, callbacks, host);
        this.view = new FileListView(tr, actions, entries, wrapped);
        this.view.setHighlightedRow(highlightedRow);
        this.topBar = FileListToolbarFactory.build(tr, type, wrapped, host);
        this.bottomBar = bottomBar;
        if (this.topBar != null) {
            this.addChild(this.topBar);
        }
        this.addChild(this.view);
        if (this.bottomBar != null) {
            this.addChild(this.bottomBar);
        }
    }

    /** 列表组件（init 后可用）。 */
    public FileListView getView() {
        return this.view;
    }

    /** 整体替换条目（保持滚动与高亮），供保存 / 生成成功后刷新使用。 */
    public void setEntries(List<FileEntry> entries) {
        this.view.setEntries(entries);
    }

    /** 按路径即时刷新选中行高亮，并滚动到可见区。 */
    public void highlight(Path path) {
        this.view.setHighlightedRowByPath(path);
    }

    /** 重新排布（条目已就地更新后调用）。 */
    public void refresh() {
        this.view.layout();
    }

    /**
     * 包装业务回调，统一落实宿主联动：
     * <ul>
     *   <li>onSelect：先执行业务回调（更新 BUYER），再从 BUYER.highlightPathFor(type)
     *       取路径刷新高亮（高亮铁律，独立屏与仪表盘共用）；</li>
     *   <li>onSave：业务保存/生成成功后——SCHEMATIC 切回 LIST 视图并刷新高亮，
     *       其余类型刷新当前列表 + 高亮（均委托宿主）。</li>
     * </ul>
     */
    private FileListView.Callbacks wrapCallbacks(FileListType type, FileListView.Callbacks cb,
                                                 FileListHost host) {
        return new FileListView.Callbacks() {
            @Override
            public void onOpen(FileEntry entry) {
                cb.onOpen(entry);
            }

            @Override
            public void onSelect(FileEntry entry) {
                cb.onSelect(entry);
                // 高亮路径唯一来源 = BUYER 选中状态（点击 → BUYER 更新 → 这里取路径 → 高亮）
                RootLayout.this.view.setHighlightedRowByPath(
                        QShopAutoBuyMod.BUYER.highlightPathFor(type));
            }

            @Override
            public void onSave(String name, Consumer<Boolean> done) {
                cb.onSave(name, ok -> {
                    done.accept(ok);
                    if (ok) {
                        if (type == FileListType.SCHEMATIC) {
                            // 原理图生成成功：回到 LIST 视图（新清单已自动选中）
                            host.switchToListAndHighlight();
                        } else {
                            // 新建/保存/生成成功：重扫当前列表并刷新高亮
                            host.refreshList();
                        }
                    }
                });
            }

            @Override
            public String defaultSaveName() {
                return cb.defaultSaveName();
            }
        };
    }

    @Override
    public void layout() {
        int topH = this.topBar == null ? 0 : TOOLBAR_H;
        int bottomH = this.bottomBar == null ? 0 : TOOLBAR_H;
        if (this.topBar != null) {
            this.topBar.setBounds(0, 0, this.width, TOOLBAR_H);
            this.topBar.layout();
        }
        if (this.bottomBar != null) {
            this.bottomBar.setBounds(0, this.height - TOOLBAR_H, this.width, TOOLBAR_H);
            this.bottomBar.layout();
        }
        this.view.setBounds(0, topH, this.width, this.height - topH - bottomH);
        this.view.layout();
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 根容器自身无内容
    }
}
