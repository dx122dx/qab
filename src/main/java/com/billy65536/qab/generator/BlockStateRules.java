package com.billy65536.qab.generator;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

import java.util.Locale;
import java.util.Map;

/**
 * 方块状态规则表：基于方块状态判定「是否跳过」「数量倍数」「是否换成别的物品」。
 *
 * <p>这是材料清单准确性的核心。同一个方块 ID 在不同状态下所需材料可能完全不同：
 * <ul>
 *   <li><b>双台阶</b> {@code type=double} —— 一个方块位置实际用了 <b>2</b> 个台阶</li>
 *   <li><b>雪层 / 海泡菜 / 蜡烛</b> —— {@code layers=5} 意味着要 <b>5</b> 个，不是 1 个</li>
 *   <li><b>门 / 床 / 高草的上半部</b> —— 与下半部同属<b>一个</b>物品，
 *       上半部必须跳过，否则数量会<b>翻倍</b></li>
 *   <li><b>流体</b> —— 只有源方块（{@code level=0}）能用桶装，流动的部分不需要买</li>
 * </ul>
 *
 * <h3>为什么优先读 {@link Properties} 常量而非状态字符串</h3>
 * <p>属性常量是方块自己声明的<b>语义</b>，模组方块只要复用原版属性就能被自动识别；
 * 而字符串匹配只认得死记的键名。仅当方块未在注册表中（跨版本/缺失模组，
 * 此时无法还原 {@link BlockState}）才回退到原始状态串做尽力判断。
 *
 * @see BlockStateResolver 负责把原理图状态还原为 BlockState
 */
public final class BlockStateRules {

    /**
     * 状态规则的判定结果。
     *
     * @param skip       是否跳过该方块（不计入清单，也不计入「无法购买」）
     * @param multiplier 数量倍数（如双台阶 = 2、雪层 = 层数）
     * @param itemId     强制指定的物品 ID；为 null 表示交由常规映射链决定
     */
    public record StateResult(boolean skip, int multiplier, String itemId) {

        private static final StateResult SKIP = new StateResult(true, 0, null);
        private static final StateResult NORMAL = new StateResult(false, 1, null);

        /** 跳过该方块。（不能命名为 skip，会与记录存取方法冲突） */
        public static StateResult skipped() {
            return SKIP;
        }

        /** 常规处理：数量 1，物品由常规映射链决定。 */
        public static StateResult normal() {
            return NORMAL;
        }

        /** 常规物品，但数量为 n。 */
        public static StateResult times(int n) {
            return n <= 1 ? NORMAL : new StateResult(false, n, null);
        }

        /** 指定物品，数量 1。 */
        public static StateResult item(String itemId) {
            return new StateResult(false, 1, itemId);
        }
    }

    private BlockStateRules() {
    }

    /**
     * 依据方块状态判定计数规则。
     *
     * @param blockId 带命名空间的方块 ID
     * @param state   还原出的方块状态；方块未注册时可为 null
     * @param states  原理图原始状态串，作为 state 为 null 时的回退依据
     * @return 判定结果，永不为 null
     */
    public static StateResult evaluate(String blockId, BlockState state, Map<String, String> states) {
        if (state == null) {
            // 方块无法还原（跨版本或缺失模组），退化为字符串判断
            return evaluateByStrings(states);
        }

        // 1) 多格方块的「非主格」：与主格共用同一个物品，必须跳过以免翻倍。
        //    门/高草/向日葵等用 DOUBLE_BLOCK_HALF，床用 BED_PART。
        if (has(state, Properties.DOUBLE_BLOCK_HALF)
                && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return StateResult.skipped();
        }
        if (has(state, Properties.BED_PART)
                && state.get(Properties.BED_PART) == BedPart.HEAD) {
            return StateResult.skipped();
        }

        // 2) 流体：仅源方块（level=0）可用桶装，流动部分是源方块蔓延的产物。
        //    注意流体方块自身的 LEVEL_15 才是液面高度；含水方块的 WATERLOGGED 不在此处理
        //    （含水方块的水通常由建造时另行处理，且其本体方块仍需正常计数）。
        String fluidBucket = fluidBucketFor(blockId);
        if (fluidBucket != null) {
            if (has(state, Properties.LEVEL_15) && state.get(Properties.LEVEL_15) != 0) {
                return StateResult.skipped();
            }
            return StateResult.item(fluidBucket);
        }

        // 3) 双台阶：一格里塞了两个台阶
        if (has(state, Properties.SLAB_TYPE)
                && state.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) {
            return StateResult.times(2);
        }

        // 4) 按数量堆叠的方块：雪层 / 蜡烛 / 海泡菜，属性值即为所需个数
        if (has(state, Properties.LAYERS)) {
            return StateResult.times(state.get(Properties.LAYERS));
        }
        if (has(state, Properties.CANDLES)) {
            return StateResult.times(state.get(Properties.CANDLES));
        }
        if (has(state, Properties.PICKLES)) {
            return StateResult.times(state.get(Properties.PICKLES));
        }

        return StateResult.normal();
    }

    /**
     * 方块无法还原时的回退判断：只依据原始状态字符串，尽力避免最严重的重复计数。
     */
    private static StateResult evaluateByStrings(Map<String, String> states) {
        if (states == null || states.isEmpty()) {
            return StateResult.normal();
        }
        if ("upper".equalsIgnoreCase(states.get("half"))) {
            return StateResult.skipped();
        }
        if ("head".equalsIgnoreCase(states.get("part"))) {
            return StateResult.skipped();
        }
        if ("double".equalsIgnoreCase(states.get("type"))) {
            return StateResult.times(2);
        }
        Integer layers = parseInt(states.get("layers"));
        if (layers != null) return StateResult.times(layers);
        Integer candles = parseInt(states.get("candles"));
        if (candles != null) return StateResult.times(candles);
        Integer pickles = parseInt(states.get("pickles"));
        if (pickles != null) return StateResult.times(pickles);
        return StateResult.normal();
    }

    /** 流体方块 → 对应的桶物品；非流体返回 null。 */
    private static String fluidBucketFor(String blockId) {
        if (blockId == null) return null;
        return switch (blockId.toLowerCase(Locale.ROOT)) {
            case "minecraft:water", "minecraft:flowing_water" -> "minecraft:water_bucket";
            case "minecraft:lava", "minecraft:flowing_lava" -> "minecraft:lava_bucket";
            default -> null;
        };
    }

    /** 判断状态是否含有该属性（不同方块的属性表不同，取值前必须先确认）。 */
    private static boolean has(BlockState state, Property<?> property) {
        return state.contains(property);
    }

    private static Integer parseInt(String value) {
        if (value == null) return null;
        try {
            int n = Integer.parseInt(value.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
