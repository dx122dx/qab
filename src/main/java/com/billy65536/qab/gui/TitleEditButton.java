package com.billy65536.qab.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 标题右上角「编辑」按钮的绘制与命中辅助，供 ShoppingListScreen / PlanScreen 共用。
 *
 * <p>纯静态无状态：矩形按屏幕宽度与翻译键文本自适应，点击打开统一编辑页的动作
 * 仍由各 Screen 自行实现（参数不同，无法下沉）。</p>
 */
public final class TitleEditButton {

    private TitleEditButton() {
    }

    /** 按钮矩形（标题右上角）：{x, y, w, h}。 */
    public static int[] rect(TextRenderer textRenderer, int screenWidth, String labelKey) {
        String label = Text.translatable(labelKey).getString();
        int w = textRenderer.getWidth(label) + 12;
        return new int[]{screenWidth - 8 - w, 4, w, 16};
    }

    /** 命中检测：鼠标坐标是否落在按钮矩形内。 */
    public static boolean hit(double mouseX, double mouseY, int[] r) {
        return mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
    }

    /** 绘制半透明底 + 文字居中。 */
    public static void render(DrawContext graphics, TextRenderer textRenderer, int screenWidth, String labelKey) {
        int[] r = rect(textRenderer, screenWidth, labelKey);
        graphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x33000000);
        String label = Text.translatable(labelKey).getString();
        graphics.drawTextWithShadow(textRenderer, Text.literal(label),
                r[0] + (r[2] - textRenderer.getWidth(label)) / 2,
                r[1] + (r[3] - 9) / 2, 0xFFFFFFFF);
    }
}
