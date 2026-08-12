package com.billy65536.qab.integration;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import net.minecraft.client.gui.screen.Screen;

/**
 * Cloth Config / AutoConfig 集成。
 *
 * <p>配置界面由配置类（{@link com.billy65536.qab.config.QabConfig} /
 * {@link com.billy65536.qab.config.SchematicConfig}）的对象结构自动生成，
 * 条目标签与工具提示来自语言文件（{@code text.autoconfig.qab_config.option.*} /
 * {@code text.autoconfig.qab_schematic.option.*} 及其 {@code .@Tooltip} 后缀）。
 *
 * <p>Cloth Config 是必需依赖（见 {@code fabric.mod.json}），无需运行时降级检测。
 */
public final class ClothConfigIntegration {

    private ClothConfigIntegration() {}

    /**
     * 创建配置界面。
     *
     * @param configClass 配置类（QabConfig 或 SchematicConfig，须实现 {@link ConfigData}）
     * @param parent      返回时的父界面
     */
    public static <T extends ConfigData> Screen createConfigScreen(Class<T> configClass, Screen parent) {
        return AutoConfig.getConfigScreen(configClass, parent).get();
    }
}
