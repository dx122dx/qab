package com.billy65536.qab.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置加载器：AutoConfig 双 holder 的薄封装。
 *
 * <p>持久化完全由 AutoConfig 的 {@code GsonConfigSerializer} 接管：
 * <ul>
 *   <li>{@code qab:config} 段 → {@code config/qab_config.json}（{@link QabConfig}）</li>
 *   <li>{@code qab:schematic} 段 → {@code config/qab_schematic.json}（{@link SchematicConfig}）</li>
 * </ul>
 *
 * <p>{@link #register()} 必须在任何 {@link #getConfig()} / {@link #getSchematicConfig()}
 * 调用之前执行（即 {@code onInitializeClient} 的最开头）。
 * 所有 getter 均通过 {@code holder.getConfig()} 现取，避免持有陈旧实例。</p>
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab.config");

    private ConfigLoader() {}

    private static ConfigHolder<QabConfig> configHolder;
    private static ConfigHolder<SchematicConfig> schematicHolder;

    /**
     * 注册 AutoConfig 两个配置段。必须在客户端初始化最开头调用一次。
     */
    public static void register() {
        if (configHolder != null && schematicHolder != null) return;
        configHolder = AutoConfig.register(QabConfig.class, GsonConfigSerializer::new);
        schematicHolder = AutoConfig.register(SchematicConfig.class, GsonConfigSerializer::new);
        LOGGER.info("AutoConfig registered for QabConfig / SchematicConfig.");
    }

    /** 返回 AutoConfig 持有的 qab:config 段活动实例（现取，避免陈旧引用）。 */
    public static QabConfig getConfig() {
        return configHolder().getConfig();
    }

    /** 返回 AutoConfig 持有的 qab:schematic 段活动实例（现取，避免陈旧引用）。 */
    public static SchematicConfig getSchematicConfig() {
        return schematicHolder().getConfig();
    }

    /** 将 qab:config 段当前配置持久化到磁盘。 */
    public static void saveConfig() {
        configHolder().save();
    }

    /** 将 qab:schematic 段当前配置持久化到磁盘。 */
    public static void saveSchematic() {
        schematicHolder().save();
    }

    private static ConfigHolder<QabConfig> configHolder() {
        if (configHolder == null) {
            throw new IllegalStateException(
                    "ConfigLoader.register() must be called before accessing the qab:config.");
        }
        return configHolder;
    }

    private static ConfigHolder<SchematicConfig> schematicHolder() {
        if (schematicHolder == null) {
            throw new IllegalStateException(
                    "ConfigLoader.register() must be called before accessing the qab:schematic.");
        }
        return schematicHolder;
    }
}
