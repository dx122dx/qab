package com.billy65536.qab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * QAB 运行时配置（JSON：{gameDir}/config/qab/qab.json）。
 *
 * <p>仅包含 QAB 自身需要的可配置项，不依赖 chunkscanner 的配置类。</p>
 *
 * <h3>字段</h3>
 * <ul>
 *   <li>{@code buyDelayMs}：到达告示牌后、发送购买命令前的等待毫秒数（默认 500）；</li>
 *   <li>{@code buyCommand}：到达后执行的购买命令模板，
 *       形如 {@code /qs amount {count}}，{@code {count}} 被替换为计划购买总量；</li>
 *   <li>{@code clickReachDist}：判定"准星可点击到告示牌"的最大距离（默认 5.0 方块）。</li>
 * </ul>
 */
public final class QabConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** QAB 配置目录：{gameDir}/config/qab/。与 BlockMappingConfig 共用，作为单一路径来源。 */
    public static final Path CONFIG_DIR = FabricLoader.getInstance().getGameDir()
            .resolve("config").resolve("qab");

    private static final int DEFAULT_BUY_DELAY_MS = 500;
    private static final String DEFAULT_BUY_COMMAND = "/qs amount {count}";
    private static final double DEFAULT_CLICK_REACH_DIST = 5.0;

    /** 到达后发送购买命令前的延时（毫秒）。 */
    private int buyDelayMs = DEFAULT_BUY_DELAY_MS;
    /** 购买命令模板，{@code {count}} 占位符替换为购买总量。 */
    private String buyCommand = DEFAULT_BUY_COMMAND;
    /** 准星可点击告示牌的最大距离（方块）。 */
    private double clickReachDist = DEFAULT_CLICK_REACH_DIST;

    private QabConfig() {
    }

    /**
     * 从默认路径加载配置：{gameDir}/config/qab/qab.json。
     * 文件不存在时使用默认值；解析失败回退默认值并告警。
     *
     * @return 加载后的配置（永不返回 null）
     */
    public static QabConfig load() {
        QabConfig cfg = new QabConfig();
        Path path = CONFIG_DIR.resolve("qab.json");
        if (!Files.exists(path)) {
            LOGGER.info("QAB config not found at {}, using defaults.", path);
            return cfg;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            QabConfig parsed = GSON.fromJson(json, QabConfig.class);
            if (parsed != null) {
                // 修复非法值
                if (parsed.buyDelayMs < 0) parsed.buyDelayMs = DEFAULT_BUY_DELAY_MS;
                if (parsed.buyCommand == null || parsed.buyCommand.isBlank()) {
                    parsed.buyCommand = DEFAULT_BUY_COMMAND;
                }
                if (parsed.clickReachDist <= 0) parsed.clickReachDist = DEFAULT_CLICK_REACH_DIST;
                cfg = parsed;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load QAB config from {}, using defaults: {}", path, e.getMessage());
        }
        return cfg;
    }

    public int getBuyDelayMs() {
        return buyDelayMs;
    }

    public String getBuyCommand() {
        return buyCommand;
    }

    public double getClickReachDist() {
        return clickReachDist;
    }
}
