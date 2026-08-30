package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import net.minecraft.client.gui.DrawContext;

/**
 * 可纵向滚动的列布局基类（仪表盘左右列共用）。
 *
 * <p>子类声明内容总高 {@link #contentHeight()}，并实现 {@link #renderContent} 绘制内容
 * （内容坐标，渲染时由本类统一裁剪到视口并施加滚动偏移）。内容高度超过本节点高度时
 * 显示垂直滚动条，滚轮在视口内滚动。</p>
 *
 * <p>事件约定：{@link #onMouseClicked} 等事件回调收到的仍是视口局部坐标，子类命中检测
 * 需用 {@link #getScrollOffset()} 还原为内容坐标（渲染回调已自动还原）。</p>
 */
public abstract class ScrollableColumn extends AbstractLayout {

    /** 滚动条宽度。 */
    protected static final int SCROLLBAR_WIDTH = 6;
    /** 滚动条轨道背景。 */
    private static final int TRACK_COLOR = 0x66000000;
    /** 滚动条 thumb。 */
    private static final int THUMB_COLOR = 0x99AAAAAA;
    /** 滚轮一格滚动像素。 */
    private static final int SCROLL_STEP = 24;

    /** 内容滚动偏移（内容坐标 → 视口坐标，向上为正）。 */
    private int scrollOffset;

    /** 内容总高度（内容坐标；超出视口高度部分可滚动查看）。 */
    public abstract int contentHeight();

    /** 当前滚动偏移。 */
    public int getScrollOffset() {
        return this.scrollOffset;
    }

    /** 最大滚动偏移（内容超出视口的部分；未超出为 0）。 */
    public int getMaxScroll() {
        return Math.max(0, this.contentHeight() - this.height);
    }

    protected void setScrollOffset(int offset) {
        this.scrollOffset = Math.max(0, Math.min(offset, this.getMaxScroll()));
    }

    /** 事件回调局部坐标 → 内容坐标（命中检测用）。 */
    protected int contentMouseY(double mouseY) {
        return (int) mouseY + this.scrollOffset;
    }

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int maxScroll = this.getMaxScroll();
        if (maxScroll <= 0) {
            // 内容未超出视口：直接绘制，无裁剪与滚动
            this.renderContent(ctx, mouseX, mouseY, delta);
            return;
        }
        this.setScrollOffset(this.scrollOffset); // 视口变化后收敛偏移
        // 裁剪到列视口（参数为右下角坐标，非宽高）
        ctx.enableScissor(this.absX, this.absY, this.absX + this.width, this.absY + this.height);
        try {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, -this.scrollOffset, 0);
            this.renderContent(ctx, mouseX, mouseY + this.scrollOffset, delta);
        } finally {
            // 矩阵与 scissor 必须配对：renderContent 抛异常时也要恢复，
            // 否则残留偏移/裁剪会把后续绘制（如 TAIL 阶段的 toast）挡掉。
            ctx.getMatrices().pop();
            ctx.disableScissor();
        }

        // 垂直滚动条
        int sx = this.width - SCROLLBAR_WIDTH;
        int thumbH = Math.max(20, this.height * this.height / this.contentHeight());
        int thumbY = (this.height - thumbH) * this.scrollOffset / maxScroll;
        ctx.fill(sx, 0, this.width, this.height, TRACK_COLOR);
        ctx.fill(sx, thumbY, this.width, thumbY + thumbH, THUMB_COLOR);
    }

    /** 子类绘制内容。mouseX/mouseY 为内容坐标（视口局部坐标 + 滚动偏移）。 */
    protected abstract void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta);

    @Override
    protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < 0 || mouseX > this.width || mouseY < 0 || mouseY > this.height) {
            return false;
        }
        if (this.getMaxScroll() <= 0) {
            return false;
        }
        this.setScrollOffset(this.scrollOffset - (int) Math.round(amount * SCROLL_STEP));
        return true;
    }
}
