package com.billy65536.qab.generator;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.billy65536.qab.config.BlockMappingConfig;
import com.billy65536.qab.config.ConfigLoader;

/**
 * 将原理图中的<b>方块 ID</b> 解析为实际可购买的<b>物品 ID</b>。
 *
 * <p>动机：原理图记录的是方块状态，而商店售卖的是物品，两者 ID 并不总是一致。
 * 典型如墙上火把 {@code minecraft:wall_torch}——它没有对应物品，玩家实际要买的是
 * {@code minecraft:torch}。若直接把方块 ID 写进购物清单，
 * {@code ShoppingPlanner} 的严格 ID 相等匹配会永远失败，条目会静默落入 failed 列表。
 *
 * <p>解析优先级（逐级回退）：
 * <ol>
 *   <li><b>状态级跳过</b>：门/床/高草的上半部、流动的液体等，直接跳过（见 {@link BlockStateRules}）</li>
 *   <li><b>不可获得集</b>：火、活塞头等纯技术性方块，直接丢弃，不计入清单</li>
 *   <li><b>特例表</b>：作物/藤蔓等不规则映射（如 {@code carrots → carrot}）</li>
 *   <li><b>组合方块</b>：一个方块拆成多个物品（如 {@code potted_X → flower_pot + X}）</li>
 *   <li><b>状态级特例</b>：液体源方块换成桶、双台阶 ×2、雪层/蜡烛/海泡菜按数量</li>
 *   <li><b>后缀规则</b>：墙上变体去掉 {@code wall_} 段（覆盖告示牌/旗帜/头颅/珊瑚扇等）</li>
 *   <li><b>注册表</b>：{@code Registries.BLOCK.get(id).asItem()}，自动覆盖绝大多数
 *       原版方块<i>以及模组方块</i>，无需硬编码</li>
 * </ol>
 *
 * <p>把注册表放在规则之后、而非之前，是因为墙上变体的 {@code asItem()} 会返回
 * {@link Items#AIR}（{@code Item.BLOCK_ITEMS} 中无对应项），必须先由规则处理。
 * 反之，注册表兜底保证了模组方块与未来版本新增方块也能正确解析。
 *
 * <p>用户配置（{@link BlockMappingConfig}）的三张表<b>优先于</b>状态级特例，
 * 使玩家始终能覆盖内置行为。
 */
public final class BlockItemResolver {

    /**
     * 解析结果。
     *
     * @param items        解析出的物品 ID 及其倍数（一个方块可能对应多个物品）
     * @param unobtainable 该方块是否无法通过购买获得（此时 items 为空）
     * @param skip         是否应完全跳过（既不计入清单，也不计入「无法购买」提示）。
     *                     用于多格方块的非主格等「本就不该被统计」的情形，
     *                     与 unobtainable 的「想买但买不到」语义不同
     */
    public record Resolved(Map<String, Integer> items, boolean unobtainable, boolean skip) {

        private static final Resolved NONE = new Resolved(Map.of(), true, false);
        private static final Resolved SKIP = new Resolved(Map.of(), false, true);

        /** 无法购买的方块。（不能命名为 unobtainable，会与记录存取方法冲突） */
        static Resolved none() {
            return NONE;
        }

        /** 应被完全忽略的方块（如门的上半部）。 */
        static Resolved skipped() {
            return SKIP;
        }

        static Resolved of(String itemId) {
            return new Resolved(Map.of(itemId, 1), false, false);
        }

        static Resolved of(String first, String second) {
            Map<String, Integer> map = new LinkedHashMap<>();
            map.merge(first, 1, Integer::sum);
            map.merge(second, 1, Integer::sum);
            return new Resolved(map, false, false);
        }

        /** 按倍数放大各物品数量；倍数 <= 1 时原样返回。 */
        Resolved scaled(int multiplier) {
            if (multiplier <= 1 || items.isEmpty()) {
                return this;
            }
            Map<String, Integer> map = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                map.put(e.getKey(), e.getValue() * multiplier);
            }
            return new Resolved(map, unobtainable, skip);
        }

        /**
         * 额外计入若干物品（如含水方块的水桶）。
         * 额外物品数量同样按倍数缩放（含水双台阶 = 2 格水 → 2 桶）。
         */
        Resolved withExtra(List<String> extraItemIds, int multiplier) {
            if (skip || unobtainable || items.isEmpty()
                    || extraItemIds == null || extraItemIds.isEmpty()) {
                return this;
            }
            Map<String, Integer> map = new LinkedHashMap<>(items);
            int count = Math.max(1, multiplier);
            for (String id : extraItemIds) {
                map.merge(id, count, Integer::sum);
            }
            return new Resolved(map, false, false);
        }
    }

    /** 合并后的映射表（来自 BlockMappingConfig，reload 后刷新）。 */
    private static volatile BlockMappingConfig.Merged mappings = BlockMappingConfig.current();

    /**
     * 解析结果缓存，键为「方块 ID + 状态串」。
     *
     * <p>原理图中同状态方块高度重复（数万方块通常只有几十种状态组合），
     * 缓存可把状态还原与规则判定的开销摊薄到近乎为零。
     * 必须在 {@link #refresh()} 时清空，否则玩家改了映射配置却仍读到旧结果。
     */
    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();

    private BlockItemResolver() {
    }

    /**
     * 由 {@link BlockMappingConfig#reloadFrom(com.billy65536.qab.config.SchematicConfig)} 在配置刷新后调用，
     * 使本类的静态缓存指向最新合并表。
     */
    public static void refresh() {
        mappings = BlockMappingConfig.current();
        CACHE.clear();
    }

    /**
     * 将<b>带方块状态</b>的方块解析为可购买的物品 ID 及数量。
     *
     * <p>这是生成材料清单的主入口。相比只看方块 ID 的重载，它能正确处理
     * 双台阶（×2）、雪层/蜡烛/海泡菜（按数量）、门床上半部（跳过）与液体源（→桶）。
     *
     * <p>结果按「方块 ID + 状态」缓存，重复方块不会重复计算。
     *
     * @param blockId 方块 ID（含命名空间、不含状态）
     * @param states  原理图记录的方块状态，可为 null 或空
     * @return 解析结果，永不为 null
     */
    public static Resolved resolve(String blockId, Map<String, String> states) {
        if (blockId == null || blockId.isEmpty()) {
            return Resolved.none();
        }
        String cacheKey = (states == null || states.isEmpty())
                ? blockId
                : blockId + states;
        return CACHE.computeIfAbsent(cacheKey, k -> resolveUncached(blockId, states));
    }

    /** 实际执行状态感知解析（未命中缓存时调用）。 */
    private static Resolved resolveUncached(String blockId, Map<String, String> states) {
        String id = normalize(blockId);
        if (id == null) {
            return Resolved.none();
        }

        // 现取含水开关（生成器为低频操作；配置变更后经 refresh() 清缓存生效）。
        // 注意：开关值不参与缓存键，但 refresh() 会清空整张缓存表，改配置后重新解析即可。
        boolean waterloggedAsBucket = ConfigLoader.getSchematicConfig().waterloggedCountsAsBucket;

        BlockState state = BlockStateResolver.resolve(id, states);
        BlockStateRules.StateResult rule = BlockStateRules.evaluate(id, state, states, waterloggedAsBucket);

        // 多格方块的非主格等：完全不参与统计
        if (rule.skip()) {
            return Resolved.skipped();
        }

        // 用户配置的三张表优先级最高，保证玩家可覆盖内置行为
        BlockMappingConfig.Merged m = mappings;
        if (m.unobtainable().contains(id)) {
            return Resolved.none();
        }
        Resolved base;
        String irregular = m.irregular().get(id);
        if (irregular != null) {
            base = Resolved.of(irregular).scaled(rule.multiplier());
        } else {
            List<String> composite = m.composite().get(id);
            if (composite != null && composite.size() >= 2) {
                base = Resolved.of(composite.get(0), composite.get(1)).scaled(rule.multiplier());
            } else if (rule.itemId() != null) {
                // 状态规则指定了具体物品（如水 → 水桶）
                base = Resolved.of(rule.itemId()).scaled(rule.multiplier());
            } else {
                // 其余走常规映射链，最后套用状态倍数
                base = resolve(id).scaled(rule.multiplier());
            }
        }
        // 含水方块额外计水桶（含水双台阶 = 2 格水 → 2 桶）
        return base.withExtra(rule.extraItemIds(), rule.multiplier());
    }

    /**
     * 将方块 ID 解析为可购买的物品 ID（不考虑方块状态）。
     *
     * <p>解析优先级：不可获得集 → 不规则表 → 组合方块 → 后缀规则（potted/candle/wall）
     * → Minecraft 注册表兜底。前三张表来自 {@link BlockMappingConfig}（内置默认 + 配置文件合并），
     * 可在不重启游戏的情况下经 {@code /qab generate list} 重新加载。
     *
     * @param blockId 已规范化的方块 ID（含命名空间、无方块状态）
     * @return 解析结果，永不为 null
     */
    public static Resolved resolve(String blockId) {
        String id = normalize(blockId);
        if (id == null) {
            return Resolved.none();
        }

        // 读取当前生效的合并表（reload 后指向新实例）
        BlockMappingConfig.Merged m = mappings;
        if (m.unobtainable().contains(id)) {
            return Resolved.none();
        }

        String irregular = m.irregular().get(id);
        if (irregular != null) {
            return Resolved.of(irregular);
        }

        List<String> composite = m.composite().get(id);
        if (composite != null && composite.size() >= 2) {
            return Resolved.of(composite.get(0), composite.get(1));
        }

        // 花盆：potted_X → flower_pot + X（X 需自身可获得）
        String potted = stripPrefix(id, "potted_");
        if (potted != null) {
            String content = resolveSingleViaRegistry(potted);
            return content == null
                    ? Resolved.of("minecraft:flower_pot")
                    : Resolved.of("minecraft:flower_pot", content);
        }

        // 插蜡烛的蛋糕：[color_]candle_cake → cake + [color_]candle
        String candleCake = matchCandleCake(id);
        if (candleCake != null) {
            return Resolved.of("minecraft:cake", candleCake);
        }

        // 墙上变体：去掉 wall_ 段。覆盖 *_wall_sign / *_wall_banner /
        // *_wall_head / *_wall_skull / *_coral_wall_fan / *_wall_hanging_sign
        String dewalled = stripWallVariant(id);
        if (dewalled != null) {
            String viaRule = resolveSingleViaRegistry(dewalled);
            if (viaRule != null) {
                return Resolved.of(viaRule);
            }
        }

        // 注册表兜底：覆盖绝大多数原版方块及模组方块
        String direct = resolveSingleViaRegistry(id);
        if (direct != null) {
            return Resolved.of(direct);
        }

        // 无法解析：保留原 ID，交由上层作为"不可获得"提示，避免静默丢失
        return Resolved.none();
    }

    /**
     * 规范化方块 ID：去空白、转小写、补全命名空间。
     *
     * @return 规范化后的 ID；输入为空时返回 null
     */
    private static String normalize(String blockId) {
        if (blockId == null) {
            return null;
        }
        String id = blockId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return null;
        }
        // 保证后续按 ':' 切分命名空间的逻辑安全
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /**
     * 通过 Minecraft 注册表把方块 ID 转成物品 ID。
     *
     * @return 物品 ID；若方块不存在或无对应物品（asItem 返回 AIR）则为 null
     */
    private static String resolveSingleViaRegistry(String blockId) {
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null || !Registries.BLOCK.containsId(identifier)) {
            return null;
        }
        Item item = Registries.BLOCK.get(identifier).asItem();
        if (item == null || item == Items.AIR) {
            return null;
        }
        return Registries.ITEM.getId(item).toString();
    }

    /**
     * 去掉墙上变体标记。例如：
     * {@code oak_wall_sign → oak_sign}、{@code white_wall_banner → white_banner}、
     * {@code tube_coral_wall_fan → tube_coral_fan}、{@code wall_torch → torch}。
     *
     * @return 去掉 wall 段后的 ID；若不是墙上变体则为 null
     */
    private static String stripWallVariant(String id) {
        int colon = id.indexOf(':');
        String namespace = id.substring(0, colon + 1);
        String path = id.substring(colon + 1);

        String result;
        if (path.startsWith("wall_")) {
            result = path.substring("wall_".length());
        } else if (path.contains("_wall_")) {
            result = path.replace("_wall_", "_");
        } else {
            return null;
        }
        return namespace + result;
    }

    /** 匹配 {@code [color_]candle_cake}，返回对应蜡烛物品 ID；不匹配返回 null。 */
    private static String matchCandleCake(String id) {
        int colon = id.indexOf(':');
        String namespace = id.substring(0, colon + 1);
        String path = id.substring(colon + 1);

        if (path.equals("candle_cake")) {
            return namespace + "candle";
        }
        if (path.endsWith("_candle_cake")) {
            String color = path.substring(0, path.length() - "_candle_cake".length());
            return namespace + color + "_candle";
        }
        return null;
    }

    /** 去掉路径前缀，返回带命名空间的剩余 ID；不匹配返回 null。 */
    private static String stripPrefix(String id, String prefix) {
        int colon = id.indexOf(':');
        String namespace = id.substring(0, colon + 1);
        String path = id.substring(colon + 1);
        return path.startsWith(prefix) ? namespace + path.substring(prefix.length()) : null;
    }
}
