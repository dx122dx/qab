package com.billy65536.qab.generator;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * 把原理图中的「方块 ID + 状态键值对」还原为真实的 Minecraft {@link BlockState}。
 *
 * <p>动机：原理图里的方块状态原本只是一组字符串（如 {@code type=double}、
 * {@code layers=5}），单靠字符串匹配判断「这是不是双台阶」既脆弱又无法覆盖模组方块。
 * 还原成 {@code BlockState} 后，就能用
 * {@code state.get(Properties.SLAB_TYPE)} 这类<b>语义化</b>取值，
 * 由方块自己声明的属性表来决定含义，规则表因此能自动适配模组方块。
 *
 * <h3>容错策略</h3>
 * <p>原理图可能来自<b>其他游戏版本</b>，其状态名/取值未必存在于当前版本
 * （典型如 1.12 的旧命名）。此类对任何无法识别的属性一律<b>静默跳过</b>，
 * 保留已成功套用的部分，绝不抛异常——材料清单宁可少算一个属性，
 * 也不该因为一个陌生状态就整体失败。
 *
 * @see BlockStateRules 基于还原结果判定数量与物品的规则表
 */
public final class BlockStateResolver {

    private BlockStateResolver() {
    }

    /**
     * 还原方块状态。
     *
     * @param blockId 带命名空间的方块 ID（如 {@code minecraft:oak_slab}）
     * @param states  原理图记录的状态键值对，可为 null 或空
     * @return 套用状态后的 {@link BlockState}；方块未注册时返回 null
     */
    public static BlockState resolve(String blockId, Map<String, String> states) {
        Block block = resolveBlock(blockId);
        if (block == null) {
            return null;
        }
        BlockState state = block.getDefaultState();
        if (states == null || states.isEmpty()) {
            return state;
        }

        StateManager<Block, BlockState> manager = block.getStateManager();
        for (Map.Entry<String, String> entry : states.entrySet()) {
            Property<?> property = manager.getProperty(entry.getKey());
            if (property == null) {
                // 当前版本没有这个属性（多为跨版本原理图），跳过
                continue;
            }
            state = applyProperty(state, property, entry.getValue());
        }
        return state;
    }

    /**
     * 解析方块 ID 为 {@link Block}。
     *
     * @return 对应方块；ID 非法或未注册时返回 null
     */
    public static Block resolveBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(blockId);
        // 未注册的 ID 会返回 AIR 兜底值，需显式排除
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        return Registries.BLOCK.get(id);
    }

    /**
     * 套用单个属性值。
     *
     * <p>独立成泛型方法是必需的：{@link Property} 的类型参数在
     * {@code Property<?>} 处被擦除为通配符，而 {@code BlockState.with} 要求
     * 属性与值的类型一致。借助方法级类型参数 {@code T} 把两者绑定，
     * 才能在不使用裸类型的前提下通过编译。
     *
     * @return 套用成功则返回新状态；值非法时原样返回传入状态
     */
    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Property<T> property, String rawValue) {
        if (rawValue == null) {
            return state;
        }
        Optional<T> parsed = property.parse(rawValue);
        // 值不在该属性的合法取值域内（跨版本差异），保留默认值
        return parsed.map(value -> state.with(property, value)).orElse(state);
    }
}
