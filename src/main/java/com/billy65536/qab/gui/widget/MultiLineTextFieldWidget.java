package com.billy65536.qab.gui.widget;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.widget.AbstractSpruceWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

/**
 * 轻量多行文本输入框，仅服务 MetaEditScreen 的 desc 编辑，不引入额外依赖。
 *
 * <p>支持 Enter 换行、Backspace/Delete、上下左右光标移动、Home/End 行首行尾、
 * Ctrl+A 全选、Ctrl+C/X/V 剪贴板、滚轮滚动；文本按行渲染并裁剪到框内，
 * 光标随输入位置闪烁。基于 {@link AbstractSpruceWidget} 扩展，经 SpruceScreen
 * 聚焦体系接收键盘/字符事件。</p>
 */
public class MultiLineTextFieldWidget extends AbstractSpruceWidget {
    private static final int BORDER = 1;
    private static final int PAD_X = 4;
    private static final int PAD_Y = 3;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int BORDER_COLOR = 0xFFAAAAAA;
    private static final int BORDER_FOCUS_COLOR = 0xFFFFAA00;
    private static final int SELECTION_COLOR = 0x80333333;

    private final TextRenderer textRenderer;
    /** 全部文本（含换行符）。 */
    private String text = "";
    /** 光标位置（文本字符索引）。 */
    private int caret;
    /** 选区起点（-1 = 无选区，可与光标构成 [start, end) 区间）。 */
    private int selectionStart = -1;
    /** 首行显示偏移（向上滚动行数）。 */
    private int scrollLines;

    public MultiLineTextFieldWidget(Position position, int width, int height) {
        super(position);
        this.width = width;
        this.height = height;
        this.textRenderer = this.client.textRenderer;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        this.caret = this.text.length();
        this.selectionStart = -1;
        this.scrollLines = 0;
    }

    @Override
    public boolean requiresCursor() {
        // 文本输入需要光标，阻止 SpruceScreen 键盘导航在聚焦时 toggle 焦点
        return true;
    }

    @Override
    public boolean charTyped(char chr, int keyCode) {
        if (Character.isISOControl(chr)) {
            return false;
        }
        this.deleteSelection();
        this.insert(String.valueOf(chr));
        return true;
    }

    @Override
    protected boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (Screen.hasControlDown()) {
            if (Screen.isSelectAll(keyCode)) {
                this.selectionStart = 0;
                this.caret = this.text.length();
                return true;
            }
            if (Screen.isCopy(keyCode)) {
                String sel = this.selectedText();
                if (!sel.isEmpty()) {
                    this.client.keyboard.setClipboard(sel);
                }
                return true;
            }
            if (Screen.isCut(keyCode)) {
                String sel = this.selectedText();
                if (!sel.isEmpty()) {
                    this.client.keyboard.setClipboard(sel);
                    this.deleteSelection();
                }
                return true;
            }
            if (Screen.isPaste(keyCode)) {
                String clip = this.client.keyboard.getClipboard();
                if (clip != null && !clip.isEmpty()) {
                    this.deleteSelection();
                    this.insert(clip);
                }
                return true;
            }
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                this.deleteSelection();
                this.insert("\n");
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                this.deleteSelection();
                if (this.selectionStart < 0) {
                    this.deleteBefore();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                this.deleteSelection();
                if (this.selectionStart < 0) {
                    this.deleteAfter();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                this.moveCaret(-1);
                this.selectionStart = -1;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                this.moveCaret(1);
                this.selectionStart = -1;
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                this.moveCaretLine(-1);
                this.selectionStart = -1;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                this.moveCaretLine(1);
                this.selectionStart = -1;
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                this.caretToLineStart();
                this.selectionStart = -1;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                this.caretToLineEnd();
                this.selectionStart = -1;
                return true;
            }
            default -> {
                // 未处理键（Esc/Tab 等）放行给 Screen，保证关闭/导航正常
                return false;
            }
        }
    }

    @Override
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int fontH = this.textRenderer.fontHeight;
        int innerX = this.getX() + BORDER;
        int innerY = this.getY() + BORDER;
        int relX = (int) mouseX - innerX - PAD_X;
        int relY = (int) mouseY - innerY - PAD_Y;
        List<String> lines = this.lines();
        int line = this.scrollLines + Math.floorDiv(relY, fontH);
        if (line < 0) {
            line = 0;
        }
        if (line >= lines.size()) {
            line = lines.size() - 1;
        }
        int lineStart = 0;
        for (int i = 0; i < line; i++) {
            lineStart += lines.get(i).length() + 1;
        }
        String lineText = lines.get(line);
        int bestCol = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i <= lineText.length(); i++) {
            int w = this.textRenderer.getWidth(lineText.substring(0, i));
            int dist = Math.abs(w - relX);
            if (dist < bestDist) {
                bestDist = dist;
                bestCol = i;
            }
        }
        this.caret = lineStart + bestCol;
        this.selectionStart = -1;
        return true;
    }

    @Override
    protected boolean onMouseScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int total = this.lines().size();
        int maxScroll = Math.max(0, total - this.visibleLines());
        this.scrollLines = Math.max(0, Math.min(maxScroll, this.scrollLines - (int) verticalAmount));
        return true;
    }

    @Override
    protected void renderWidget(DrawContext graphics, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        graphics.fill(x, y, x + w, y + h, 0xFF000000);
        int borderColor = this.isFocused() ? BORDER_FOCUS_COLOR : BORDER_COLOR;
        graphics.fill(x, y, x + w, y + BORDER, borderColor);
        graphics.fill(x, y + h - BORDER, x + w, y + h, borderColor);
        graphics.fill(x, y, x + BORDER, y + h, borderColor);
        graphics.fill(x + w - BORDER, y, x + w, y + h, borderColor);

        int innerX = x + BORDER;
        int innerY = y + BORDER;
        int innerW = w - BORDER * 2;
        int innerH = h - BORDER * 2;
        int fontH = this.textRenderer.fontHeight;
        graphics.enableScissor(innerX, innerY, innerX + innerW, innerY + innerH);
        try {
            List<String> lines = this.lines();
            if (this.selectionStart >= 0) {
                this.renderSelection(graphics, innerX, innerY, fontH,
                        Math.min(this.selectionStart, this.caret),
                        Math.max(this.selectionStart, this.caret));
            }
            int textY = innerY + PAD_Y;
            int vis = this.visibleLines();
            for (int i = this.scrollLines; i < lines.size() && i < this.scrollLines + vis + 1; i++) {
                graphics.drawTextWithShadow(this.textRenderer, Text.literal(lines.get(i)),
                        innerX + PAD_X, textY, TEXT_COLOR);
                textY += fontH;
            }
            if (this.isFocused() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int[] pos = this.posFromCaret(this.caret);
                int lineStart = this.caret - pos[1];
                String prefix = this.text.substring(lineStart, this.caret);
                int cx = innerX + PAD_X + this.textRenderer.getWidth(prefix);
                int cy = innerY + PAD_Y + (pos[0] - this.scrollLines) * fontH;
                graphics.fill(cx, cy, cx + 1, cy + fontH, 0xFFFFFFFF);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /** 追加文本（粘贴 / 字符输入共用，保持光标）。 */
    private void insert(String str) {
        if (str == null || str.isEmpty()) {
            return;
        }
        this.text = this.text.substring(0, this.caret) + str + this.text.substring(this.caret);
        this.caret += str.length();
        this.selectionStart = -1;
        this.scrollToCaret();
    }

    private void deleteBefore() {
        if (this.caret <= 0) {
            return;
        }
        this.text = this.text.substring(0, this.caret - 1) + this.text.substring(this.caret);
        this.caret--;
        this.scrollToCaret();
    }

    private void deleteAfter() {
        if (this.caret >= this.text.length()) {
            return;
        }
        this.text = this.text.substring(0, this.caret) + this.text.substring(this.caret + 1);
        this.scrollToCaret();
    }

    private void deleteSelection() {
        if (this.selectionStart < 0) {
            return;
        }
        int start = Math.min(this.selectionStart, this.caret);
        int end = Math.max(this.selectionStart, this.caret);
        if (end > start) {
            this.text = this.text.substring(0, start) + this.text.substring(end);
            this.caret = start;
        }
        this.selectionStart = -1;
        this.scrollToCaret();
    }

    private String selectedText() {
        if (this.selectionStart < 0) {
            return "";
        }
        int start = Math.min(this.selectionStart, this.caret);
        int end = Math.max(this.selectionStart, this.caret);
        return this.text.substring(start, end);
    }

    /** 按换行符拆行（保留空行）。 */
    private List<String> lines() {
        return Arrays.asList(this.text.split("\n", -1));
    }

    /** 字符索引 → {行号, 列号}。 */
    private int[] posFromCaret(int caret) {
        List<String> lines = this.lines();
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (caret <= idx + line.length()) {
                return new int[]{i, caret - idx};
            }
            idx += line.length() + 1;
        }
        return new int[]{lines.size() - 1, lines.get(lines.size() - 1).length()};
    }

    /** 行号与列号 → 字符索引（列超出行长截断到行尾）。 */
    private int caretFromPos(int line, int col) {
        List<String> lines = this.lines();
        if (line >= lines.size()) {
            return this.text.length();
        }
        int idx = 0;
        for (int i = 0; i < line; i++) {
            idx += lines.get(i).length() + 1;
        }
        return idx + Math.min(col, lines.get(line).length());
    }

    private void moveCaret(int delta) {
        this.caret = Math.max(0, Math.min(this.text.length(), this.caret + delta));
        this.scrollToCaret();
    }

    private void moveCaretLine(int delta) {
        int[] pos = this.posFromCaret(this.caret);
        int target = pos[0] + delta;
        if (target < 0 || target >= this.lines().size()) {
            return;
        }
        this.caret = this.caretFromPos(target, pos[1]);
        this.scrollToCaret();
    }

    private void caretToLineStart() {
        int[] pos = this.posFromCaret(this.caret);
        this.caret = this.caretFromPos(pos[0], 0);
        this.scrollToCaret();
    }

    private void caretToLineEnd() {
        int[] pos = this.posFromCaret(this.caret);
        this.caret = this.caretFromPos(pos[0], this.lines().get(pos[0]).length());
        this.scrollToCaret();
    }

    /** 当前可见行数（框内可显示的文本行数）。 */
    private int visibleLines() {
        return Math.max(1, (this.getHeight() - PAD_Y * 2 - BORDER * 2) / this.textRenderer.fontHeight);
    }

    /** 确保光标行可见（滚动跟随）。 */
    private void scrollToCaret() {
        int[] pos = this.posFromCaret(this.caret);
        int vis = this.visibleLines();
        if (pos[0] < this.scrollLines) {
            this.scrollLines = pos[0];
        } else if (pos[0] >= this.scrollLines + vis) {
            this.scrollLines = pos[0] - vis + 1;
        }
    }

    /** 绘制选区高亮（逐行，跨行不连续）。 */
    private void renderSelection(DrawContext graphics, int innerX, int innerY, int fontH,
                                 int selStart, int selEnd) {
        List<String> lines = this.lines();
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineStart = idx;
            int lineEnd = idx + line.length();
            if (lineEnd >= selStart && lineStart <= selEnd) {
                int from = Math.max(lineStart, selStart);
                int to = Math.min(lineEnd, selEnd);
                int sx = innerX + PAD_X + this.textRenderer.getWidth(line.substring(0, from - lineStart));
                int ex = innerX + PAD_X + this.textRenderer.getWidth(line.substring(0, to - lineStart));
                int y = innerY + PAD_Y + (i - this.scrollLines) * fontH;
                graphics.fill(sx, y, ex, y + fontH, SELECTION_COLOR);
            }
            idx = lineEnd + 1;
            if (lineStart > selEnd) {
                break;
            }
        }
    }
}
