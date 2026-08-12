package com.billy65536.qab.generator;

import net.minecraft.client.MinecraftClient;

import net.sandrohc.schematic4j.SchematicLoader;
import net.sandrohc.schematic4j.schematic.Schematic;
import net.sandrohc.schematic4j.schematic.types.SchematicBlock;
import net.sandrohc.schematic4j.schematic.types.SchematicBlockEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.config.SchematicConfig;
import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *   <li><b>按方块状态精确计数</b>：双台阶算 2 个、雪层/蜡烛/海泡菜按数量、
 *       门床与高草的上半部跳过（避免翻倍）、液体源方块换算成桶。
 *       详见 {@link BlockStateRules}</li>
 *   <li>经 {@link BlockItemResolver} 把方块 ID 转成实际可购买的物品 ID
 *       （如 {@code wall_torch → torch}），可用 {@code rawId=true} 关闭</li>
 *   <li>{@code blockEntity=true} 时额外统计容器<b>内部存放的物品</b>
 *       （容器方块本身始终由方块统计负责，二者不重叠）</li>
 *   <li>{@code deductInventory=true} 时扣除玩家背包（含潜影盒）已有物品</li>
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
     * @param unobtainable    无法购买的方块 ID → 数量（如火、活塞头），已从清单剔除
     * @param stateSkipped    因方块状态规则而跳过的方块数（门床上半部、流动液体等）
     * @param containerItems  从容器内部统计到的物品总数
     * @param deducted        因玩家已持有而扣除的物品 ID → 数量
     * @param inventoryUnavailable 请求了库存扣除但玩家不可用（未进入世界）
     */
    public record Result(ShoppingList list, int blockTypes, long totalBlocks, int skipped,
                         int width, int height, int length,
                         Map<String, Long> unobtainable,
                         long stateSkipped, long containerItems,
                         Map<String, Long> deducted, boolean inventoryUnavailable) {
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
            // 传入方块状态，交由解析链做精确计数
            acc.add(block.block(), block.states());
        }

        if (config.includeBlockEntitiesOrDefault()) {
            var beIt = schematic.blockEntities().iterator();
            while (beIt.hasNext()) {
                SchematicBlockEntity be = beIt.next();
                if (be == null) continue;
                // 只统计容器内含物：容器方块本身已在上面的方块遍历中计过，
                // 若在此处再按 be.name() 加一次就会重复计数。
                ContainerItemCounter.count(be, acc::addItem);
            }
        }

        Map<String, Long> counts = acc.counts;
        long totalBlocks = acc.totalBlocks;
        int skipped = acc.skipped;

        List<ShoppingItem> items = buildItems(counts, config);

        // 库存扣除置于倍率/最小值/阈值之后：倍率放大的是「需求」，
        // 若先扣除再放大，会把玩家已有的部分也一并放大，得出错误结果。
        Map<String, Long> deducted = new LinkedHashMap<>();
        boolean inventoryUnavailable = false;
        if (config.deductInventoryOrDefault()) {
            MinecraftClient client = MinecraftClient.getInstance();
            var player = client == null ? null : client.player;
            if (player == null) {
                inventoryUnavailable = true;
                LOGGER.warn("deductInventory requested but no player available; skipping deduction");
            } else {
                items = deductInventory(items, PlayerInventoryCounter.countAll(player), deducted);
            }
        }

        ShoppingList list = new ShoppingList();
        list.setVersion(LIST_VERSION);
        list.setName(resolveName(schematicPath, schematic, config));
        list.setDescription(resolveDescription(schematicPath, schematic, config, items.size(), totalBlocks));
        list.setRedundancy(config.redundancyOrDefault());
        list.setItems(items);

        LOGGER.info("Generated list from {}: {} type(s), {} block(s), {} skipped, {} state-skipped, {} container item(s)",
                schematicPath.getFileName(), items.size(), totalBlocks, skipped,
                acc.stateSkipped, acc.containerItems);

        return new Result(list, items.size(), totalBlocks, skipped,
                schematic.width(), schematic.height(), schematic.length(),
                acc.unobtainable, acc.stateSkipped, acc.containerItems,
                deducted, inventoryUnavailable);
    }

    /**
     * 从清单中扣除玩家已持有的物品，只保留还缺的部分。
     *
     * @param items    原始清单项
     * @param owned    玩家持有量
     * @param deducted 输出参数：记录实际扣除的物品与数量，供命令回显
     * @return 扣除后的清单（已完全满足的物品会被移除）
     */
    private static List<ShoppingItem> deductInventory(List<ShoppingItem> items,
                                                      Map<String, Long> owned,
                                                      Map<String, Long> deducted) {
        if (owned.isEmpty()) {
            return items;
        }
        List<ShoppingItem> result = new ArrayList<>(items.size());
        for (ShoppingItem item : items) {
            long have = owned.getOrDefault(item.getId(), 0L);
            if (have <= 0) {
                result.add(item);
                continue;
            }
            long need = item.getCount();
            long used = Math.min(have, need);
            deducted.merge(item.getId(), used, Long::sum);

            long remaining = need - used;
            if (remaining > 0) {
                result.add(new ShoppingItem(item.getId(), (int) remaining));
            }
            // remaining == 0：已完全满足，不再列入清单
        }
        return result;
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
        /** 因方块状态规则被跳过的方块数（上半部、流动液体等）。 */
        long stateSkipped = 0L;
        /** 从容器内部统计到的物品总数。 */
        long containerItems = 0L;

        Accumulator(ListGenConfig config) {
            this.config = config;
        }

        /**
         * 统计一个方块。
         *
         * @param rawId  原始方块 ID
         * @param states 方块状态，可为 null
         */
        void add(String rawId, Map<String, String> states) {
            String blockId = normalizeId(rawId);
            if (blockId == null || isAir(blockId)) return;

            // 排除判断基于原始方块 ID，符合用户书写直觉
            // （如 exclude=*_wall_sign 应当拦住墙上告示牌）
            if (isExcluded(blockId, config.excludes)) {
                skipped++;
                return;
            }

            if (config.rawIdOrDefault()) {
                // 调试模式保留原始方块 ID，但仍需套用状态跳过规则，
                // 否则门/床的上半部依旧会造成翻倍。
                // 含水开关：rawId 模式仅取 multiplier/skip，额外水桶由常规解析链负责，
                // 这里只关心跳过与倍数，含水方块的本体数量与额外水桶仍由 BlockItemResolver 计。
                BlockStateRules.StateResult rule = BlockStateRules.evaluate(
                        blockId, BlockStateResolver.resolve(blockId, states), states,
                        ConfigLoader.getSchematicConfig().waterloggedCountsAsBucket);
                if (rule.skip()) {
                    stateSkipped++;
                    return;
                }
                int n = Math.max(1, rule.multiplier());
                counts.merge(blockId, (long) n, Long::sum);
                totalBlocks += n;
                return;
            }

            BlockItemResolver.Resolved resolved = BlockItemResolver.resolve(blockId, states);
            if (resolved.skip()) {
                stateSkipped++;
                return;
            }
            if (resolved.unobtainable()) {
                unobtainable.merge(blockId, 1L, Long::sum);
                return;
            }
            for (Map.Entry<String, Integer> e : resolved.items().entrySet()) {
                counts.merge(e.getKey(), (long) e.getValue(), Long::sum);
                totalBlocks += e.getValue();
            }
        }

        /**
         * 直接统计一个物品（用于容器内含物，无需再走方块→物品映射）。
         *
         * @param itemId 物品 ID
         * @param amount 数量
         */
        void addItem(String itemId, long amount) {
            if (itemId == null || amount <= 0) return;
            if (isExcluded(itemId, config.excludes)) {
                skipped++;
                return;
            }
            counts.merge(itemId, amount, Long::sum);
            totalBlocks += amount;
            containerItems += amount;
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
            if (scaled > Integer.MAX_VALUE) {
                // 状态倍数（如 layers=8）叠加高倍率后可能溢出，截断并留痕便于排查
                LOGGER.warn("Count for {} exceeds int range ({}), clamped to {}",
                        e.getKey(), scaled, Integer.MAX_VALUE);
            }
            int count = (int) Math.min(scaled, Integer.MAX_VALUE);
            items.add(new ShoppingItem(e.getKey(), count));
        }

        Comparator<ShoppingItem> comparator = config.sortOrDefault() == SchematicConfig.SortMode.ID
                ? Comparator.comparing(ShoppingItem::getId)
                : Comparator.comparingInt(ShoppingItem::getCount).reversed()
                        .thenComparing(ShoppingItem::getId);
        items.sort(comparator);
        return items;
    }

    private static String resolveName(Path path, Schematic schematic, ListGenConfig config) {
        String name = config.nameOrDefault();
        if (name != null) {
            return name;
        }
        String schematicName = schematic.name();
        if (schematicName != null && !schematicName.isBlank()) {
            return schematicName;
        }
        return stripExtension(path.getFileName().toString());
    }

    private static String resolveDescription(Path path, Schematic schematic, ListGenConfig config,
                                             int types, long total) {
        String description = config.descriptionOrDefault();
        if (description != null) {
            return description;
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

    /**
     * 规范化方块 ID：补全命名空间。
     *
     * <p>schematic4j 的 {@code block()} 已剥离方块状态，此处仍兜底去掉 {@code [...]}
     * 以防原理图数据异常。
     */
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
