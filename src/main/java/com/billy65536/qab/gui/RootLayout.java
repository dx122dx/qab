package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;

/**
 * 文件列表根容器：上方工具条（可选）+ 列表 + 下方工具条（可选）。
 *
 * <p>仅负责槽位排布，不特化工具条组件——topBar / bottomBar 由调用方用
 * 现有布局组件（SaveBarLayout、按钮布局等）组装后传入；列表占剩余区域。
 * 事件按 {@link AbstractLayout} 逆序分发，工具条与列表不重叠故互不影响。</p>
 */
public class RootLayout extends AbstractLayout {

    /** 工具条槽位高度（与 {@link FileListView#SAVE_BAR_H} 对齐）。 */
    public static final int TOOLBAR_H = 34;

    private final AbstractLayout list;
    @Nullable
    private final AbstractLayout topBar;
    @Nullable
    private final AbstractLayout bottomBar;

    public RootLayout(AbstractLayout list, @Nullable AbstractLayout topBar, @Nullable AbstractLayout bottomBar) {
        this.list = list;
        this.topBar = topBar;
        this.bottomBar = bottomBar;
        if (topBar != null) {
            this.addChild(topBar);
        }
        this.addChild(list);
        if (bottomBar != null) {
            this.addChild(bottomBar);
        }
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
        this.list.setBounds(0, topH, this.width, this.height - topH - bottomH);
        this.list.layout();
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 根容器自身无内容
    }
}
