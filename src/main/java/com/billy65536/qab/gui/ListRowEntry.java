package com.billy65536.qab.gui;

import com.billy65536.qab.planner.model.ShoppingItem;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.widget.container.SpruceEntryListWidget;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 清单列表中的一行（SpruceUI Entry）。
 *
 * <p>负责行渲染（序号/图标/名称/ID/详情三行 + 现有/需求/冗余数量 + 四个行按钮），
 * 以及行内编辑态（内嵌 {@link SpruceTextFieldWidget} 修改需求数量：
 * 回车确认、Esc 取消、失焦自动确认）。所有行操作经 {@link ShoppingListScreen} 回调
 * {@link com.billy65536.qab.gui.IListSource}，保存动作由屏幕底部的「保存」按钮统一触发。</p>
 */
public class ListRowEntry extends SpruceEntryListWidget.Entry {

    private static final int COLOR_GRAY = 0xFFAAAAAA;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_YELLOW = 0xFFFFFF55;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_LIGHT_BLUE = 0xFFAACCFF;
    private static final int COLOR_BTN_BG = 0x66000000;
    private static final int COLOR_BTN_HOVER = 0xAA666666;
    private static final int COLOR_BTN_DELETE_HOVER = 0x99FF5555;
    private static final int COLOR_ROW_HOVER = 0x18FFFFFF;
    private static final int COLOR_ROW_LINE = 0x33000000;
    private static final int COLOR_PLACEHOLDER = 0xFF555555;

    /** 行按钮图标：上移 / 下移 / 编辑 / 删除。 */
    private static final String[] BTN_LABELS = {"↑", "↓", "编", "×"};

    private static final int BTN_TOP = 3;
    private static final float SCALE_SMALL = 0.8f;

    private final ShoppingListScreen screen;
    private final ShoppingItem item;
    private final int index;
    private SpruceTextFieldWidget editor;

    public ListRowEntry(ShoppingListScreen screen, ShoppingItem item, int index) {
        this.screen = screen;
        this.item = item;
        this.index = index;
        this.width = screen.getRowWidth();
        this.height = ShoppingListScreen.ROW_HEIGHT;
    }

    public ShoppingItem getItem() {
        return this.item;
    }

    @Override
    protected void renderWidget(DrawContext graphics, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        var font = this.client.textRenderer;
        boolean hover = this.isMouseOver(mouseX, mouseY);

        // 行悬停高亮 + 底部分隔线
        if (hover) {
            graphics.fill(x, y, x + w, y + this.height, COLOR_ROW_HOVER);
        }
        graphics.fill(x, y + this.height - 1, x + w, y + this.height, COLOR_ROW_LINE);

        int iconX = x + ShoppingListScreen.SEQ_W;
        int iconY = y + (this.height - 16) / 2;
        int midY = y + (this.height - 9) / 2;

        // 序号
        String seq = String.valueOf(this.index + 1);
        graphics.drawText(font, seq, x + (ShoppingListScreen.SEQ_W - font.getWidth(seq)) / 2, midY, COLOR_GRAY, false);

        // 物品图标（非法 ID 渲染灰色占位）
        ItemStack stack = this.getStack();
        if (stack.isEmpty()) {
            graphics.fill(iconX, iconY, iconX + 16, iconY + 16, COLOR_PLACEHOLDER);
        } else {
            graphics.drawItem(stack, iconX, iconY);
        }

        // 名称（白粗）/ ID（灰小字）
        graphics.drawText(font, stack.getName(), x + this.screen.nameX(), y + 1, COLOR_WHITE, true);
        String id = this.item.getId();
        if (id != null && !id.isBlank()) {
            this.drawScaled(graphics, Text.literal(id), x + this.screen.idX(), y + 10, SCALE_SMALL, COLOR_GRAY);
        }

        // 详情（黄小字：附魔、NBT 要求，最多两行）
        List<String> details = this.buildDetails();
        int detY = y + 14;
        for (int i = 0; i < details.size() && i < 2; i++) {
            this.drawScaled(graphics, Text.literal(details.get(i)), x + this.screen.detX(), detY + i * 5, SCALE_SMALL, COLOR_YELLOW);
        }

        // 数量：现有绿 / 需求黄（编辑态由输入框覆盖）/ 冗余浅蓝（清单级，仅展示）
        int have = this.screen.getHaveCount(id);
        this.drawCentered(graphics, have < 0 ? "?" : String.valueOf(have),
                x + this.screen.haveX(), x + this.screen.haveX() + this.screen.haveW(), COLOR_GREEN);
        if (this.editor == null) {
            this.drawCentered(graphics, String.valueOf(this.item.getCount()),
                    x + this.screen.needX(), x + this.screen.needX() + this.screen.needW(), COLOR_YELLOW);
        }
        this.drawCentered(graphics, "+" + this.screen.getRedundancy(),
                x + this.screen.redunX(), x + this.screen.redunX() + this.screen.redunW(), COLOR_LIGHT_BLUE);

        // 行按钮
        int by = y + BTN_TOP;
        int bh = this.height - BTN_TOP * 2;
        for (int i = 0; i < BTN_LABELS.length; i++) {
            int btnX = x + this.screen.btnsX() + i * (ShoppingListScreen.BTN_W + ShoppingListScreen.BTN_GAP);
            boolean btnHover = hover && this.inButton(mouseX, mouseY, i);
            int bg = btnHover && i == 3 ? COLOR_BTN_DELETE_HOVER : (btnHover ? COLOR_BTN_HOVER : COLOR_BTN_BG);
            graphics.fill(btnX, by, btnX + ShoppingListScreen.BTN_W, by + bh, bg);
            String label = BTN_LABELS[i];
            graphics.drawText(font, label, btnX + (ShoppingListScreen.BTN_W - font.getWidth(label)) / 2,
                    by + (bh - 9) / 2, COLOR_WHITE, false);
        }

        // 图标 tooltip（悬停显示原版提示）
        if (hover && !stack.isEmpty()
                && mouseX >= iconX && mouseX < iconX + 16
                && mouseY >= iconY && mouseY < iconY + 16) {
            var player = this.client.player;
            if (player != null) {
                graphics.drawTooltip(font, stack.getTooltip(player, TooltipContext.BASIC), mouseX, mouseY);
            }
        }

        // 编辑框（最后绘制，覆盖需求数字）
        if (this.editor != null) {
            this.editor.render(graphics, mouseX, mouseY, delta);
        }
    }

    /* ---- 交互 ---- */

    @Override
    protected boolean onMouseClick(double mouseX, double mouseY, int button) {
        if (this.editor != null) {
            if (this.editor.isMouseOver(mouseX, mouseY)) {
                return this.editor.mouseClicked(mouseX, mouseY, button);
            }
            this.commitEdit(); // 失焦自动确认
        }
        if (button != 0) {
            return false;
        }
        for (int i = 0; i < BTN_LABELS.length; i++) {
            if (this.inButton(mouseX, mouseY, i)) {
                this.onButton(i);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (this.editor == null) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.commitEdit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.cancelEditor();
            return true;
        }
        return this.editor.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean onCharTyped(char chr, int keyCode) {
        return this.editor != null && this.editor.charTyped(chr, keyCode);
    }

    /* ---- 行操作 ---- */

    private void onButton(int button) {
        switch (button) {
            case 0 -> this.screen.moveUp(this.index);
            case 1 -> this.screen.moveDown(this.index);
            case 2 -> this.startEdit();
            case 3 -> this.screen.removeItem(this.index);
        }
    }

    private void startEdit() {
        this.cancelEditor();
        this.editor = new SpruceTextFieldWidget(
                Position.of(this, this.screen.needX(), (this.height - 14) / 2),
                this.screen.needW(), 14,
                Text.empty(), Text.empty());
        this.editor.setTextPredicate(SpruceTextFieldWidget.INTEGER_INPUT_PREDICATE);
        this.editor.setText(String.valueOf(this.item.getCount()));
        this.editor.setCursorToEnd();
        this.editor.setFocused(true);
    }

    private void commitEdit() {
        if (this.editor == null) {
            return;
        }
        String text = this.editor.getText().trim();
        this.cancelEditor();
        if (text.isEmpty()) {
            return;
        }
        try {
            int value = Integer.parseInt(text);
            if (value >= 0) {
                this.item.setCount(value);
            }
        } catch (NumberFormatException ignored) {
            // 非法输入：保持原值
        }
    }

    private void cancelEditor() {
        this.editor = null;
    }

    /* ---- 渲染辅助 ---- */

    private ItemStack getStack() {
        String id = this.item.getId();
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(Registries.ITEM.get(identifier));
    }

    private List<String> buildDetails() {
        List<String> out = new ArrayList<>(4);
        var enchants = this.item.getEnchant();
        if (enchants != null) {
            for (var entry : enchants.entrySet()) {
                Identifier eid = Identifier.tryParse(entry.getKey());
                Enchantment enchantment = eid == null ? null : Registries.ENCHANTMENT.get(eid);
                if (enchantment != null) {
                    out.add(enchantment.getName(entry.getValue()).getString());
                } else {
                    out.add(entry.getKey());
                }
            }
        }
        String nbt = this.item.getMatchNbt();
        if (nbt != null && !nbt.isBlank()) {
            out.add("NBT: " + nbt);
        }
        return out;
    }

    private boolean inButton(double mouseX, double mouseY, int i) {
        int x = this.getX() + this.screen.btnsX() + i * (ShoppingListScreen.BTN_W + ShoppingListScreen.BTN_GAP);
        int y = this.getY() + BTN_TOP;
        int bh = this.height - BTN_TOP * 2;
        return mouseX >= x && mouseX < x + ShoppingListScreen.BTN_W
                && mouseY >= y && mouseY < y + bh;
    }

    private void drawCentered(DrawContext graphics, String text, int startX, int endX, int color) {
        var font = this.client.textRenderer;
        int cx = startX + (endX - startX) / 2;
        graphics.drawText(font, text, cx - font.getWidth(text) / 2, this.getY() + (this.height - 9) / 2, color, false);
    }

    private void drawScaled(DrawContext graphics, Text text, int x, int y, float scale, int color) {
        var matrices = graphics.getMatrices();
        matrices.push();
        matrices.scale(scale, scale, 1f);
        graphics.drawText(this.client.textRenderer, text, (int) (x / scale), (int) (y / scale), color, false);
        matrices.pop();
    }
}
