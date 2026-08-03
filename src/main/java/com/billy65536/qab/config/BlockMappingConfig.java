package com.billy65536.qab.config;

import com.billy65536.qab.generator.BlockItemResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方块→物品映射配置（JSON：{gameDir}/config/qab/block-mapping.json）。
 *
 * <p>把 {@link BlockItemResolver} 中原本写死的三张表外置为可编辑配置文件：
 * <ul>
 *   <li>{@code unobtainable} —— 无法购买的方块 ID 列表（流体、火、活塞头等）</li>
 *   <li>{@code irregular} —— 不规则单映射：{@code 方块ID: 物品ID}</li>
 *   <li>{@code composite} —— 组合方块：{@code 方块ID: [物品ID, 物品ID, ...]}</li>
 * </ul>
 *
 * <p><b>合并策略</b>：内置默认表（{@code DEFAULT_*}）作为出厂值；配置文件存在时，
 * {@code irregular}/{@code composite} 按 key 覆盖内置同名项并可追加新键，
 * {@code unobtainable} 取内置与配置的<b>并集</b>。配置文件缺某字段则该字段全用内置值。
 * 后缀规则与注册表兜底属于算法，仍保留在 {@link BlockItemResolver} 代码中。
 *
 * <p><b>加载时机</b>：每次 {@code /qab generate list} 执行时调用 {@link #reload()}，
 * 实时刷新合并结果，改 JSON 无需重启游戏。文件不存在或解析失败时回退内置默认表并告警。
 */
public final class BlockMappingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab/block-mapping");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 配置文件路径：{gameDir}/config/qab/block-mapping.json。 */
    public static final Path MAPPING_FILE = QabConfig.CONFIG_DIR.resolve("block-mapping.json");

    /** 合并后的当前生效表，reload() 后刷新。volatile 保证命令线程可见性。 */
    private static volatile Merged merged = buildDefault();

    private BlockMappingConfig() {
    }

    /**
     * 重新加载并合并配置。每次命令执行时调用。
     * 任何异常都不会抛出，而是回退内置默认表并告警。
     *
     * @return true 表示使用了外部配置文件；false 表示回退到内置默认表
     */
    public static boolean reload() {
        if (!Files.exists(MAPPING_FILE)) {
            LOGGER.info("Block mapping file not found at {}, using built-in defaults.", MAPPING_FILE);
            merged = buildDefault();
            BlockItemResolver.refresh();
            return false;
        }
        try {
            String json = Files.readString(MAPPING_FILE, StandardCharsets.UTF_8);
            Raw raw = GSON.fromJson(json, Raw.class);
            merged = raw == null ? buildDefault() : merge(raw);
            BlockItemResolver.refresh();
            LOGGER.info("Block mapping loaded: {} unobtainable, {} irregular, {} composite.",
                    merged.unobtainable().size(), merged.irregular().size(), merged.composite().size());
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to load block mapping from {}, using built-in defaults: {}",
                    MAPPING_FILE, e.getMessage());
            merged = buildDefault();
            BlockItemResolver.refresh();
            return false;
        }
    }

    /** 返回当前生效的合并表（供 {@link BlockItemResolver} 读取）。 */
    public static Merged current() {
        return merged;
    }

    // ---- 合并后的结果 ----
    public record Merged(Set<String> unobtainable,
                  Map<String, String> irregular,
                  Map<String, List<String>> composite) {
    }

    /** JSON 反序列化结构（允许字段缺失）。 */
    private static final class Raw {
        List<String> unobtainable;
        Map<String, String> irregular;
        Map<String, List<String>> composite;
    }

    // ---- 内置默认表（原 BlockItemResolver 中的三张表，作为出厂值） ----

    private static final Set<String> DEFAULT_UNOBTAINABLE = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void",
            "minecraft:water", "minecraft:lava", "minecraft:bubble_column",
            "minecraft:fire", "minecraft:soul_fire",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:piston_head", "minecraft:moving_piston",
            "minecraft:frosted_ice",
            "minecraft:attached_melon_stem", "minecraft:attached_pumpkin_stem",
            "minecraft:bamboo_sapling", "minecraft:cave_vines_plant",
            "minecraft:kelp_plant", "minecraft:twisting_vines_plant",
            "minecraft:weeping_vines_plant", "minecraft:big_dripleaf_stem"
    );

    private static final Map<String, String> DEFAULT_IRREGULAR = Map.ofEntries(
            Map.entry("minecraft:wall_torch", "minecraft:torch"),
            Map.entry("minecraft:soul_wall_torch", "minecraft:soul_torch"),
            Map.entry("minecraft:redstone_wall_torch", "minecraft:redstone_torch"),
            Map.entry("minecraft:redstone_wire", "minecraft:redstone"),
            Map.entry("minecraft:tripwire", "minecraft:string"),
            Map.entry("minecraft:carrots", "minecraft:carrot"),
            Map.entry("minecraft:potatoes", "minecraft:potato"),
            Map.entry("minecraft:beetroots", "minecraft:beetroot_seeds"),
            Map.entry("minecraft:melon_stem", "minecraft:melon_seeds"),
            Map.entry("minecraft:pumpkin_stem", "minecraft:pumpkin_seeds"),
            Map.entry("minecraft:cocoa", "minecraft:cocoa_beans"),
            Map.entry("minecraft:torchflower_crop", "minecraft:torchflower_seeds"),
            Map.entry("minecraft:pitcher_crop", "minecraft:pitcher_pod"),
            Map.entry("minecraft:sweet_berry_bush", "minecraft:sweet_berries"),
            Map.entry("minecraft:cave_vines", "minecraft:glow_berries"),
            Map.entry("minecraft:tall_seagrass", "minecraft:seagrass"),
            Map.entry("minecraft:powder_snow", "minecraft:powder_snow_bucket")
    );

    private static final Map<String, List<String>> DEFAULT_COMPOSITE = Map.of(
            "minecraft:water_cauldron", List.of("minecraft:cauldron", "minecraft:water_bucket"),
            "minecraft:lava_cauldron", List.of("minecraft:cauldron", "minecraft:lava_bucket"),
            "minecraft:powder_snow_cauldron", List.of("minecraft:cauldron", "minecraft:powder_snow_bucket")
    );

    /** 仅内置默认、无合并。 */
    private static Merged buildDefault() {
        return new Merged(
                new HashSet<>(DEFAULT_UNOBTAINABLE),
                new LinkedHashMap<>(DEFAULT_IRREGULAR),
                new LinkedHashMap<>(DEFAULT_COMPOSITE)
        );
    }

    /** 内置默认表 + 配置文件覆盖/追加。 */
    private static Merged merge(Raw raw) {
        Set<String> unobtainable = new HashSet<>(DEFAULT_UNOBTAINABLE);
        if (raw.unobtainable != null) {
            unobtainable.addAll(raw.unobtainable);
        }

        Map<String, String> irregular = new LinkedHashMap<>(DEFAULT_IRREGULAR);
        if (raw.irregular != null) {
            irregular.putAll(raw.irregular);
        }

        Map<String, List<String>> composite = new LinkedHashMap<>(DEFAULT_COMPOSITE);
        if (raw.composite != null) {
            composite.putAll(raw.composite);
        }

        return new Merged(unobtainable, irregular, composite);
    }
}
