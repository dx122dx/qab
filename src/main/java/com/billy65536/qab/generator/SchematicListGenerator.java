package com.billy65536.qab.generator;

import net.sandrohc.schematic4j.SchematicLoader;
import net.sandrohc.schematic4j.schematic.Schematic;
import net.sandrohc.schematic4j.schematic.types.SchematicBlock;
import net.sandrohc.schematic4j.schematic.types.SchematicBlockEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 schematic4j 解析原理图（.litematic / .schem / .schematic / .nbt），
 * 统计所需方块并生成 {@link ShoppingList}。
 *
 * <p>统计规则：
 * <ul>
 *   <li>忽略空气类方块（air / cave_air / void_air / structure_void）</li>
 *   <li>忽略方块状态，仅按方块 ID 聚合</li>
 *   <li>经 {@link BlockItemResolver} 把方块 ID 转成实际可购买的物品 ID
 *       （如 {@code wall_torch → torch}），可用 {@code rawId=true} 关闭</li>
 *   <li>按配置应用倍率、冗余、阈值、排除与排序</li>
 * </ul>
 */
public class SchematicListGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab/list-gen");

    /** 清单格式版本。 */
    private static final int LIST_VERSION = 1;

    /** 空气类方块（不计入清单）。 */
    private static final List<String> AIR_BLOCKS = List.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");

    /**
     * 生成结果，附带统计信息用于命令回显。
     *
     * @param unobtainable 无法购买的方块 ID → 数量（如流体、火、活塞头），已从清单剔除
     */
    public record Result(ShoppingList list, int blockTypes, long totalBlocks, int skipped,
                         int width, int height, int length,
                         Map<String, Long> unobtainable) {
    }

    private SchematicListGenerator() {
    }

    /**
     * 解析原理图并生成购物清单。
     *
     * @param schematicPath 原理图文件路径
     * @param config        生成配置（不可为 null）
     * @return 生成结果
     * @throws Exception 解析或读取失败
     */
    public static Result generate(Path schematicPath, ListGenConfig config) throws Exception {
        Schematic schematic = SchematicLoader.load(schematicPath);
        if (schematic == null) {
            throw new IllegalStateException("Parser returned no schematic");
        }

        Accumulator acc = new Accumulator(config);

        var it = schematic.blocks().iterator();
        while (it.hasNext()) {
            SchematicBlock block = it.next().right();
            if (block == null) continue;
            acc.add(block.block());
        }

        if (config.includeBlockEntitiesOrDefault()) {
            var beIt = schematic.blockEntities().iterator();
            while (beIt.hasNext()) {
                SchematicBlockEntity be = beIt.next();
                if (be == null) continue;
                acc.add(be.name());
            }
        }

        Map<String, Long> counts = acc.counts;
        long totalBlocks = acc.totalBlocks;
        int skipped = acc.skipped;

        List<ShoppingItem> items = buildItems(counts, config);

        ShoppingList list = new ShoppingList();
        list.setVersion(LIST_VERSION);
        list.setName(resolveName(schematicPath, schematic, config));
        list.setDescription(resolveDescription(schematicPath, schematic, config, items.size(), totalBlocks));
        list.setRedundancy(config.redundancyOrDefault());
        list.setItems(items);

        LOGGER.info("Generated list from {}: {} type(s), {} block(s), {} skipped",
                schematicPath.getFileName(), items.size(), totalBlocks, skipped);

        return new Result(list, items.size(), totalBlocks, skipped,
                schematic.width(), schematic.height(), schematic.length(),
                acc.unobtainable);
    }

    /**
     * 方块计数累加器：统一处理规范化、排除、方块→物品映射与不可获得统计，
     * 使方块与方块实体两条统计路径行为一致。
     */
    private static final class Accumulator {
        private final ListGenConfig config;
        final Map<String, Long> counts = new HashMap<>();
        final Map<String, Long> unobtainable = new HashMap<>();
        long totalBlocks = 0L;
        int skipped = 0;

        Accumulator(ListGenConfig config) {
            this.config = config;
        }

        void add(String rawId) {
            String blockId = normalizeId(rawId);
            if (blockId == null || isAir(blockId)) return;

            // 排除判断基于原始方块 ID，符合用户书写直觉
            // （如 exclude=*_wall_sign 应当拦住墙上告示牌）
            if (isExcluded(blockId, config.excludes)) {
                skipped++;
                return;
            }

            if (config.rawIdOrDefault()) {
                counts.merge(blockId, 1L, Long::sum);
                totalBlocks++;
                return;
            }

            BlockItemResolver.Resolved resolved = BlockItemResolver.resolve(blockId);
            if (resolved.unobtainable()) {
                unobtainable.merge(blockId, 1L, Long::sum);
                return;
            }
            for (Map.Entry<String, Integer> e : resolved.items().entrySet()) {
                counts.merge(e.getKey(), (long) e.getValue(), Long::sum);
                totalBlocks += e.getValue();
            }
        }
    }

    /** 将统计结果转换为清单项，应用倍率 / 最小值 / 阈值 / 排序。 */
    private static List<ShoppingItem> buildItems(Map<String, Long> counts, ListGenConfig config) {
        double multiplier = config.multiplierOrDefault();
        int min = config.minCountOrDefault();
        int threshold = config.thresholdOrDefault();

        List<ShoppingItem> items = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            long scaled = (long) Math.ceil(e.getValue() * multiplier);
            if (scaled < min) {
                scaled = min;
            }
            if (scaled < threshold || scaled <= 0) {
                continue;
            }
            int count = (int) Math.min(scaled, Integer.MAX_VALUE);
            items.add(new ShoppingItem(e.getKey(), count));
        }

        Comparator<ShoppingItem> comparator = config.sortOrDefault() == ListGenConfig.SortMode.ID
                ? Comparator.comparing(ShoppingItem::getId)
                : Comparator.comparingInt(ShoppingItem::getCount).reversed()
                        .thenComparing(ShoppingItem::getId);
        items.sort(comparator);
        return items;
    }

    private static String resolveName(Path path, Schematic schematic, ListGenConfig config) {
        if (config.name != null && !config.name.isBlank()) {
            return config.name;
        }
        String schematicName = schematic.name();
        if (schematicName != null && !schematicName.isBlank()) {
            return schematicName;
        }
        return stripExtension(path.getFileName().toString());
    }

    private static String resolveDescription(Path path, Schematic schematic, ListGenConfig config,
                                             int types, long total) {
        if (config.description != null && !config.description.isBlank()) {
            return config.description;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Generated from ").append(path.getFileName())
                .append(" (").append(schematic.format()).append(", ")
                .append(schematic.width()).append('x')
                .append(schematic.height()).append('x')
                .append(schematic.length()).append("); ")
                .append(types).append(" block type(s), ").append(total).append(" block(s)");
        String author = schematic.author();
        if (author != null && !author.isBlank()) {
            sb.append("; author ").append(author);
        }
        return sb.toString();
    }

    /** 去掉方块状态并补全命名空间。 */
    private static String normalizeId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return null;
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket).trim();
        }
        if (id.isEmpty()) return null;
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        return id;
    }

    private static boolean isAir(String id) {
        return AIR_BLOCKS.contains(id);
    }

    /**
     * 判断方块是否被排除。模式支持：完整 ID、省略命名空间的短名、以及 {@code *} 前后缀通配。
     */
    private static boolean isExcluded(String id, List<String> patterns) {
        if (patterns.isEmpty()) return false;
        String shortId = id.substring(id.indexOf(':') + 1);
        for (String pattern : patterns) {
            if (matches(id, pattern) || matches(shortId, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String value, String pattern) {
        boolean prefixWildcard = pattern.startsWith("*");
        boolean suffixWildcard = pattern.endsWith("*") && pattern.length() > 1;
        String core = pattern.substring(prefixWildcard ? 1 : 0,
                pattern.length() - (suffixWildcard ? 1 : 0));
        if (core.isEmpty()) {
            return prefixWildcard || suffixWildcard;
        }
        if (prefixWildcard && suffixWildcard) return value.contains(core);
        if (prefixWildcard) return value.endsWith(core);
        if (suffixWildcard) return value.startsWith(core);
        return value.equals(pattern);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
