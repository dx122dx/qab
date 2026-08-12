package com.billy65536.qab;

import java.util.Collection;
import java.util.List;

import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.config.SchematicConfig;
import com.billy65536.qab.integration.ClothConfigIntegration;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;

import me.shedaniel.autoconfig.ConfigData;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * qab 作为 infrastructure 模块的接入点（{@link IModule} 实现）。
 *
 * <p>经由 Java SPI（{@code META-INF/services/...IModule}）由 infrastructure 的
 * {@code ModuleRegistry#discover()} 自动发现并登记。登记后：</p>
 * <ul>
 *   <li>{@code /inf config get|set|reset|gui|reload qab:config/...} 统一读写常规运行配置；</li>
 *   <li>{@code /inf config get|set|reset|gui|reload qab:schematic/...} 统一读写购物清单生成配置；</li>
 *   <li>{@code /inf info qab} 列出模块信息。</li>
 * </ul>
 *
 * <p>原 qab 自有的手写 Gson 配置（{@code config/qab/qab.json}、{@code config/qab/block-mapping.json}）
 * 已全部迁移到 AutoConfig（{@code config/qab_config.json}、{@code config/qab_schematic.json}），
 * 配置访问统一收归 {@code /inf config}。</p>
 */
public final class QabModule implements IModule {

    private static final String ID = "qab";

    /** 供 Java SPI 实例化；登记由 infrastructure ModuleRegistry.discover() 统一触发。 */
    public QabModule() {}

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getVersion() {
        return QShopAutoBuyMod.getVersion();
    }

    @Override
    public Text getName() {
        return Text.literal("QShop Auto Buy");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("qab.msg.module_desc");
    }

    // ==================== 配置 ====================

    @Override
    public List<ConfigDescriptor> getConfigDescriptors() {
        // 段名 "config"：完整路径形如 qab:config/buyDelayMs。
        ConfigPath configPath = ConfigPath.of(ID, "config", "");
        // 段名 "schematic"：完整路径形如 qab:schematic/waterloggedCountsAsBucket。
        ConfigPath schematicPath = ConfigPath.of(ID, "schematic", "");
        return List.of(
                ConfigDescriptor.withGui(
                        configPath,
                        ConfigLoader::getConfig,
                        new QabConfig(),
                        QabModule::openConfigGui),
                ConfigDescriptor.withGui(
                        schematicPath,
                        ConfigLoader::getSchematicConfig,
                        new SchematicConfig(),
                        QabModule::openSchematicGui));
    }

    @Override
    public void saveConfig() {
        ConfigLoader.saveConfig();
    }

    // ==================== 命令 ====================

    // 命令（/qab ...）仍由 QabCommands 在 onInitializeClient 统一注册，本模块不重复贡献命令树。
    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return null;
    }

    @Override
    public Collection<String> getCommandLiterals() {
        return List.of();
    }

    // ==================== GUI ====================

    private static void openConfigGui() {
        openGui(QabConfig.class);
    }

    private static void openSchematicGui() {
        openGui(SchematicConfig.class);
    }

    private static void openGui(Class<? extends ConfigData> configClass) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        Screen parent = client.currentScreen;
        client.setScreen(ClothConfigIntegration.createConfigScreen(configClass, parent));
    }
}
