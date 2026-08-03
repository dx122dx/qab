package com.billy65536.qab.generate;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *   <li><b>不可获得集</b>：流体、火、活塞头等纯技术性方块，直接丢弃，不计入清单</li>
 *   <li><b>特例表</b>：作物/藤蔓等不规则映射（如 {@code carrots → carrot}）</li>
 *   <li><b>组合方块</b>：一个方块拆成多个物品（如 {@code potted_X → flower_pot + X}）</li>
 *   <li><b>后缀规则</b>：墙上变体去掉 {@code wall_} 段（覆盖告示牌/旗帜/头颅/珊瑚扇等）</li>
 *   <li><b>注册表</b>：{@code Registries.BLOCK.get(id).asItem()}，自动覆盖绝大多数
 *       原版方块<i>以及模组方块</i>，无需硬编码</li>
 * </ol>
 *
 * <p>把注册表放在规则之后、而非之前，是因为墙上变体的 {@code asItem()} 会返回
 * {@link Items#AIR}（{@code Item.BLOCK_ITEMS} 中无对应项），必须先由规则处理。
 * 反之，注册表兜底保证了模组方块与未来版本新增方块也能正确解析。
 */
public final class BlockItemResolver {

    /**
     * 解析结果。
     *
     * @param items       解析出的物品 ID 及其倍数（一个方块可能对应多个物品）
     * @param unobtainable 该方块是否无法通过购买获得（此时 items 为空）
     */
    public record Resolved(Map<String, Integer> items, boolean unobtainable) {

        private static final Resolved NONE = new Resolved(Map.of(), true);

        /** 无法购买的方块。（不能命名为 unobtainable，会与记录存取方法冲突） */
        static Resolved none() {
            return NONE;
        }

        static Resolved of(String itemId) {
            return new Resolved(Map.of(itemId, 1), false);
        }

        static Resolved of(String first, String second) {
            Map<String, Integer> map = new LinkedHashMap<>();
            map.merge(first, 1, Integer::sum);
            map.merge(second, 1, Integer::sum);
            return new Resolved(map, false);
        }
    }

    /** 合并后的映射表（来自 BlockMappingConfig，reload 后刷新）。 */
    private static volatile BlockMappingConfig.Merged mappings = BlockMappingConfig.current();

    private BlockItemResolver() {
    }

    /**
     * 由 {@link BlockMappingConfig#reload()} 在配置刷新后调用，使本类的静态缓存指向最新合并表。
     * 包级可见，避免外部误调用。
     */
    static void refresh() {
        mappings = BlockMappingConfig.current();
    }

    /**
     * 将方块 ID 解析为可购买的物品 ID。
     *
     * <p>解析优先级：不可获得集 → 不规则表 → 组合方块 → 后缀规则（potted/candle/wall）
     * → Minecraft 注册表兜底。前三张表来自 {@link BlockMappingConfig}（内置默认 + 配置文件合并），
     * 可在不重启游戏的情况下经 {@code /qab generate list} 重新加载。
     *
     * @param blockId 已规范化的方块 ID（含命名空间、无方块状态）
     * @return 解析结果，永不为 null
     */
    public static Resolved resolve(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return Resolved.none();
        }
        String id = blockId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return Resolved.none();
        }
        // 保证后续按 ':' 切分命名空间的逻辑安全
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
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
