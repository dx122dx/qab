package com.billy65536.qab.config;

import com.billy65536.qab.generator.BlockItemResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方块→物品映射：内置默认表 + 用户覆盖的合并缓存（供 {@link BlockItemResolver} 读取）。
 *
 * <p>把原本写死的三张表拆成"内置默认 + 用户差异"两层：
 * <ul>
 *   <li>{@code unobtainable} —— 无法购买的方块 ID 列表（流体、火、活塞头等）</li>
 *   <li>{@code irregular} —— 不规则单映射：{@code 方块ID: 物品ID}</li>
 *   <li>{@code composite} —— 组合方块：{@code 方块ID: [物品ID, 物品ID, ...]}</li>
 * </ul>
 *
 * <p><b>合并策略</b>：内置默认表（{@code DEFAULT_*}）作为出厂值；用户差异写在
 * {@code qab:schematic} 段（{@link SchematicConfig} 的三张表）：
 * {@code irregular}/{@code composite} 按 key 覆盖内置同名项并可追加新键，
 * {@code unobtainable} 取内置与配置的<b>并集</b>。后缀规则与注册表兜底属于算法，
 * 仍保留在 {@link BlockItemResolver} 代码中。
 *
 * <p><b>物化时机</b>：每次 {@code /qab list generate} 执行时调用
 * {@link #reloadFrom(SchematicConfig)}，实时刷新合并结果，改配置无需重启游戏。
 */
public final class BlockMappingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab/block-mapping");

    private BlockMappingConfig() {
    }

    /**
     * 以内置默认为基础，用 schematic 段的用户差异表物化合并结果。
     * 每次命令执行时调用。任何异常都不会抛出，而是回退内置默认表并告警。
     *
     * @param cfg qab:schematic 段配置；null 时按全内置默认处理
     */
    public static void reloadFrom(SchematicConfig cfg) {
        if (cfg == null) {
            merged = buildDefault();
            BlockItemResolver.refresh();
            LOGGER.info("Block mapping fallback to built-in defaults (schematic config is null).");
            return;
        }
        try {
            merged = merge(cfg);
            BlockItemResolver.refresh();
            LOGGER.info("Block mapping refreshed: {} unobtainable, {} irregular, {} composite.",
                    merged.unobtainable().size(), merged.irregular().size(), merged.composite().size());
        } catch (Exception e) {
            LOGGER.warn("Failed to merge block mapping, using built-in defaults: {}", e.getMessage());
            merged = buildDefault();
            BlockItemResolver.refresh();
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

    // ---- 内置默认表（原 BlockItemResolver 中的三张表，作为出厂值） ----

    // 注意：water / lava 不在此列 —— 它们由 BlockStateRules 按 level 判定：
    // 源方块（level=0）映射为对应的桶，流动的部分则跳过。
    private static final Set<String> DEFAULT_UNOBTAINABLE = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void",
            "minecraft:bubble_column",
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

    /**
     * 合并后的当前生效表，reloadFrom() 后刷新。volatile 保证命令线程可见性。
     *
     * <p><b>声明位置不可上移</b>：静态字段初始化按源码顺序执行，此处必须位于
     * {@code DEFAULT_*} 三张表之后，否则 {@link #buildDefault()} 读到的是 null，
     * 触发 {@code ExceptionInInitializerError}。
     */
    private static volatile Merged merged = buildDefault();

    /** 仅内置默认、无合并。 */
    private static Merged buildDefault() {
        return new Merged(
                new HashSet<>(DEFAULT_UNOBTAINABLE),
                new LinkedHashMap<>(DEFAULT_IRREGULAR),
                new LinkedHashMap<>(DEFAULT_COMPOSITE)
        );
    }

    /** 内置默认表 + schematic 段用户表覆盖/追加。 */
    private static Merged merge(SchematicConfig cfg) {
        // schematic 段经 validatePostLoad 清理，但防御性剔除 null/空白项。
        Set<String> unobtainable = new HashSet<>(DEFAULT_UNOBTAINABLE);
        if (cfg.unobtainable != null) {
            for (String id : cfg.unobtainable) {
                if (id != null && !id.isBlank()) {
                    unobtainable.add(id);
                }
            }
        }

        Map<String, String> irregular = new LinkedHashMap<>(DEFAULT_IRREGULAR);
        if (cfg.irregular != null) {
            cfg.irregular.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !v.isBlank()) {
                    irregular.put(k, v);
                }
            });
        }

        Map<String, List<String>> composite = new LinkedHashMap<>(DEFAULT_COMPOSITE);
        if (cfg.composite != null) {
            cfg.composite.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && v.size() >= 2
                        && v.get(0) != null && v.get(1) != null) {
                    composite.put(k, v);
                }
            });
        }

        return new Merged(unobtainable, irregular, composite);
    }
}
