package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.ScreenContainer;
import com.billy65536.qab.gui.widget.MultiLineTextFieldWidget;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 统一名称与描述编辑页：name 用单行输入框、desc 用多行输入框，
 * 保存时写回 {@link IListSource#updateMeta}，随后返回上一级屏幕。
 */
public class MetaEditScreen extends ScreenContainer {
    private static final String KEY_TITLE = "qab.msg.meta.edit_title";
    private static final String KEY_NAME = "qab.msg.meta.name";
    private static final String KEY_DESC = "qab.msg.meta.description";
    private static final String KEY_SAVE = "qab.msg.meta.save";
    private static final String KEY_CANCEL = "qab.msg.meta.cancel";
    private static final String KEY_SAVED = "qab.msg.meta.save_success";

    private final IListSource<?> source;
    private final Screen parent;
    @Nullable
    private SpruceTextFieldWidget nameField;
    @Nullable
    private MultiLineTextFieldWidget descField;

    public MetaEditScreen(IListSource<?> source, Screen parent) {
        super(Text.translatable(KEY_TITLE));
        this.source = source;
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (this.isErrorState()) {
            return;
        }
        int w = Math.min(this.width - 80, 320);
        int cx = (this.width - w) / 2;
        this.nameField = new SpruceTextFieldWidget(Position.of(cx, 44), w, 20, Text.translatable(KEY_NAME));
        String name = this.source.getName();
        this.nameField.setText(name == null ? "" : name);
        this.addDrawableChild(this.nameField);

        int descH = Math.max(80, this.height - 190);
        this.descField = new MultiLineTextFieldWidget(Position.of(cx, 84), w, descH);
        String desc = this.source.getDescription();
        this.descField.setText(desc == null ? "" : desc);
        this.addDrawableChild(this.descField);

        int btnW = 80;
        int gap = 10;
        int y = this.height - 28;
        int total = btnW * 2 + gap;
        int startX = (this.width - total) / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(KEY_CANCEL), b -> this.client.setScreen(this.parent))
                .dimensions(startX, y, btnW, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(KEY_SAVE), b -> this.commit())
                .dimensions(startX + btnW + gap, y, btnW, 20).build());

        // 默认聚焦名称输入框，键盘事件经 Screen 分发直达
        this.setFocused(this.nameField);
    }

    /** 保存元数据并返回上一级。 */
    private void commit() {
        String name = this.nameField == null ? "" : this.nameField.getText().trim();
        String desc = this.descField == null ? "" : this.descField.getText();
        this.source.updateMeta(name, desc);
        if (this.client.player != null) {
            this.client.player.sendMessage(Text.translatable(KEY_SAVED), false);
        }
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc 直接返回上一级（不落盘）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.client.setScreen(this.parent);
            return true;
        }
        // 优先分发给聚焦的输入控件（方向键/Home/End 等编辑键），
        // 避免 SpruceScreen 键盘导航在文本编辑时截获这些按键
        Element focused = this.getFocused();
        if (focused != null && focused.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (this.isErrorState()) {
            return;
        }
        var matrices = ctx.getMatrices();
        matrices.push();
        matrices.scale(2f, 2f, 1f);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(KEY_TITLE), this.width / 4, 2, 0xFFFFFFFF);
        matrices.pop();
        ctx.fill(0, 24, this.width, 25, 0xFFFFAA00);
        if (this.nameField != null) {
            ctx.drawTextWithShadow(this.textRenderer, Text.translatable(KEY_NAME),
                    this.nameField.getX(), this.nameField.getY() - 12, 0xFFFFFFFF);
        }
        if (this.descField != null) {
            ctx.drawTextWithShadow(this.textRenderer, Text.translatable(KEY_DESC),
                    this.descField.getX(), this.descField.getY() - 12, 0xFFFFFFFF);
        }
        this.renderWidgets(ctx, mouseX, mouseY, delta);
    }
}
