package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

/**
 * 保存按钮组件（可嵌入，仅 compound 文件列表挂载）。
 *
 * <p>收起态：右侧青色「保存」大按钮；点击展开：文本框（预填默认名）+ 绿色 √ + 红色 ×。
 * Enter 提交 / Esc 取消 / 点击外部收起（由宿主转发）。提交结果经
 * {@link FileListView.Callbacks#onSave} 反馈，成功自动收起。</p>
 *
 * <p>自包含实现：渲染与命中全部由本组件完成（不依赖 SpruceUI Widget 与 Screen 注册），
 * 可作为独立组件在任意布局树中复用。</p>
 */
public class SaveBarLayout extends AbstractLayout {

    /** 组件高度（与 {@link FileListView#SAVE_BAR_H} 一致）。 */
    public static final int BAR_H = 34;
    /** 按钮高度。 */
    private static final int BTN_H = 20;
    /** 文本框宽度。 */
    private static final int FIELD_W = 200;
    /** 文件名最大长度。 */
    private static final int MAX_NAME_LEN = 64;

    private final TextRenderer tr;
    private final FileListView.Callbacks callbacks;

    private boolean expanded;
    private final StringBuilder text = new StringBuilder();
    private boolean committing;

    public SaveBarLayout(TextRenderer tr, FileListView.Callbacks callbacks) {
        this.tr = tr;
        this.callbacks = callbacks;
    }

    public boolean isExpanded() {
        return this.expanded;
    }

    /** 收起并清空输入（点击外部 / 取消按钮 / 提交成功时调用）。 */
    public void collapse() {
        this.expanded = false;
        this.text.setLength(0);
    }

    /** 展开输入态并预填默认名（清空上次残留输入；无默认名则留空）。 */
    private void expand() {
        String def = this.callbacks.defaultSaveName();
        this.text.setLength(0);
        if (def != null) {
            this.text.append(def);
        }
        this.expanded = true;
    }

    /** 提交文件名：空名忽略；committing 防重入；经 onSave 反馈结果，成功自动收起。 */
    private void commit() {
        if (this.committing) {
            return;
        }
        String name = this.text.toString().trim();
        if (name.isEmpty()) {
            return;
        }
        this.committing = true;
        this.callbacks.onSave(name, ok -> {
            this.committing = false;
            if (ok) {
                this.collapse();
            }
        });
    }

    /* ---- 布局（自绘矩形） ---- */

    private int[] saveBtnRect() {
        String label = Text.translatable("qab.msg.file_gui.save").getString();
        int w = this.tr.getWidth(label) + 14;
        int x = this.width - w - 20;
        int y = (this.height - BTN_H) / 2;
        return new int[]{x, y, w, BTN_H};
    }

    private int[] fieldRect() {
        // 展开态组合（文本框 + √ + ×）右边缘与收起态保存按钮右边缘重合（width - 20），
        // 即输入框出现在保存按钮原位置，而非居中：FIELD_W + 6 + 20 + 4 + 20 = 250
        int x = this.width - 20 - 6 - 20 - 4 - 20 - FIELD_W;
        int y = (this.height - BTN_H) / 2;
        return new int[]{x, y, FIELD_W, BTN_H};
    }

    private int[] confirmRect() {
        int[] f = this.fieldRect();
        return new int[]{f[0] + f[2] + 6, f[1], 20, BTN_H};
    }

    private int[] cancelRect() {
        int[] c = this.confirmRect();
        return new int[]{c[0] + c[2] + 4, c[1], 20, BTN_H};
    }

    private static boolean hit(int[] r, double mx, double my) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /* ---- 事件 ---- */

    @Override
    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (!this.expanded) {
            if (hit(this.saveBtnRect(), mouseX, mouseY)) {
                this.expand();
                return true;
            }
            return false;
        }
        if (hit(this.confirmRect(), mouseX, mouseY)) {
            this.commit();
            return true;
        }
        if (hit(this.cancelRect(), mouseX, mouseY)) {
            this.collapse();
            return true;
        }
        // 命中文本框：吞掉事件避免行点击兜底（不收起）
        if (hit(this.fieldRect(), mouseX, mouseY)) {
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.expanded) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.commit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.collapse();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && this.text.length() > 0) {
            this.text.deleteCharAt(this.text.length() - 1);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onCharTyped(char chr, int keyCode) {
        if (!this.expanded) {
            return false;
        }
        if (Character.isISOControl(chr) || this.text.length() >= MAX_NAME_LEN) {
            return false;
        }
        this.text.append(chr);
        return true;
    }

    /* ---- 渲染 ---- */

    @Override
    protected void renderSelf(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!this.expanded) {
            int[] r = this.saveBtnRect();
            int color = hit(r, mouseX, mouseY) ? 0xFFFFFF55 : 0xFF55FFFF;
            String label = Text.translatable("qab.msg.file_gui.save").getString();
            ctx.drawTextWithShadow(this.tr, Text.literal(label), r[0] + 7, r[1] + (BTN_H - 9) / 2, color);
            return;
        }
        int[] f = this.fieldRect();
        ctx.fill(f[0], f[1], f[0] + f[2], f[1] + f[3], 0x80000000);
        ctx.fill(f[0], f[1], f[0] + 1, f[1] + f[3], 0xFFFFAA00);
        ctx.fill(f[0] + f[2] - 1, f[1], f[0] + f[2], f[1] + f[3], 0xFFFFAA00);
        String s = this.text.toString();
        ctx.drawTextWithShadow(this.tr, Text.literal(s), f[0] + 5, f[1] + (BTN_H - 9) / 2, 0xFFFFFFFF);
        // 光标（闪烁）
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = f[0] + 5 + this.tr.getWidth(s);
            ctx.fill(cx, f[1] + 2, cx + 1, f[1] + f[3] - 2, 0xFFFFFFFF);
        }
        int[] c = this.confirmRect();
        int colorC = hit(c, mouseX, mouseY) ? 0xFFFFFF55 : 0xFF55FF55;
        ctx.drawTextWithShadow(this.tr, Text.literal("√"), c[0] + 6, c[1] + (BTN_H - 9) / 2, colorC);
        int[] xr = this.cancelRect();
        int colorX = hit(xr, mouseX, mouseY) ? 0xFFFFFF55 : 0xFFFF5555;
        ctx.drawTextWithShadow(this.tr, Text.literal("×"), xr[0] + 6, xr[1] + (BTN_H - 9) / 2, colorX);
    }
}
