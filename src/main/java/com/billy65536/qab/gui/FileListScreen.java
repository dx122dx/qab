package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.infrastructure.core.gui.toast.Messenger;
import com.billy65536.infrastructure.core.gui.toast.ToastType;
import com.billy65536.qab.QShopAutoBuyMod;
import com.billy65536.qab.QShopAutoBuyer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件列表 Screen 壳（独立屏宿主）。
 *
 * <p>构造只需「标题 + {@link FileListType} + 宿主 + 初始高亮行」：内容（动作/条目/回调/工具条）
 * 统一由 {@link QShopAutoBuyer#listConfig} 装配 + {@link RootLayout} 组合，
 * 不在此处或命令层散落组装工具条（WET 源头收敛到工厂）。</p>
 *
 * <p>本类同时实现 {@link FileListHost}：独立屏场景下自身的刷新/跳转行为，
 * {@code host} 参数可空（null 时以自身为宿主）。原理图选择屏以触发它的 list 屏为宿主，
 * 生成成功后经宿主 {@link #switchToListAndHighlight()} 回到 list gui。</p>
 */
public class FileListScreen extends ScreenContainer implements FileListHost {

    /** 列表区顶部 y（2x 标题 + 金色分隔线之下）。 */
    public static final int HEADER_Y = 28;

    private final FileListType type;
    /** 需要高亮的行索引（-1 无）。 */
    private final int highlightedRow;
    /** 宿主（null → init 时置为自身；原理图屏传触发它的 list 屏）。 */
    @Nullable
    private final FileListHost host;

    private RootLayout root;
    private FileListView view;

    public FileListScreen(Text title, FileListType type, int highlightedRow) {
        this(title, type, null, highlightedRow);
    }

    public FileListScreen(Text title, FileListType type, @Nullable FileListHost host, int highlightedRow) {
        super(title);
        this.type = type;
        this.host = host;
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

    /** 按路径即时刷新选中行高亮，并滚动到可见区。 */
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
        FileListHost effectiveHost = this.host != null ? this.host : this;
        QShopAutoBuyer.DashboardListConfig cfg = QShopAutoBuyMod.BUYER.listConfig(this.type);
        this.root = new RootLayout(this.textRenderer, this.type, cfg.actions(), cfg.entries(),
                cfg.callbacks(), effectiveHost, this.highlightedRow);
        this.view = this.root.getView();
        this.setLayout(this.root);
        super.init();
        this.root.setBounds(0, HEADER_Y, this.width, this.height - HEADER_Y);
        this.root.layout();
    }

    // ---- FileListHost（独立屏宿主） ----

    @Override
    public void refreshList() {
        if (this.view == null) {
            return;
        }
        QShopAutoBuyer.DashboardListConfig cfg = QShopAutoBuyMod.BUYER.listConfig(this.type);
        this.view.setEntries(cfg.entries());
        this.view.setHighlightedRowByPath(QShopAutoBuyMod.BUYER.highlightPathFor(this.type));
    }

    @Override
    public void openSchematicPicker() {
        // list 屏工具条「从投影文件生成购物清单」：切到原理图选择屏（隐藏视图），
        // 以当前屏为宿主，生成成功后经 switchToListAndHighlight 回到 list gui
        var client = MinecraftClient.getInstance();
        // 必须用 send（延迟到下一帧）切屏，防止聊天框关闭覆盖
        client.send(() -> client.setScreen(new FileListScreen(
                Text.translatable("qab.msg.file_gui.title_schematic"),
                FileListType.SCHEMATIC, this, -1)));
    }

    @Override
    public void switchToListAndHighlight() {
        // 原理图生成成功后回到 list gui（新清单已自动选中，进入时高亮）
        var client = MinecraftClient.getInstance();
        client.send(() -> client.setScreen(new FileListScreen(
                Text.translatable("qab.msg.file_gui.title_list"),
                FileListType.LIST, null, -1)));
    }

    @Override
    public void toast(Text text, ToastType type) {
        Messenger.notify(text, type);
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
