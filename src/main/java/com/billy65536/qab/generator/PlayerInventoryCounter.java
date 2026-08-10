package com.billy65536.qab.generator;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;

/**
 * 统计玩家<b>当前已持有</b>的物品，用于从购物清单中扣除，只买还缺的部分。
 *
 * <h3>统计范围</h3>
 * <p>覆盖玩家背包全部槽位（主背包 + 快捷栏 + 盔甲 + 副手），
 * 并<b>下潜一层</b>统计潜影盒内部的物品——建材常常整盒携带，
 * 不看盒内就会把已有材料重复买一遍。
 *
 * <p>只下潜一层而非无限递归：原版不允许潜影盒套潜影盒，
 * 更深的嵌套只可能来自异常数据，无限递归反而有栈溢出风险。
 *
 * <p>与 {@code InventoryCapacityCalculator} 的区别：那个类算「还能装多少」
 * （只看主背包 27 格，因为快捷栏留给玩家自用），本类算「已经有多少」
 * （必须统计全部槽位，因为快捷栏里的建材同样是已有库存）。
 */
public final class PlayerInventoryCounter {

    /** 潜影盒等容器方块的 NBT 根键。 */
    private static final String KEY_BLOCK_ENTITY_TAG = "BlockEntityTag";
    /** 容器内含物列表键。 */
    private static final String KEY_ITEMS = "Items";

    private PlayerInventoryCounter() {
    }

    /**
     * 统计玩家已持有的全部物品。
     *
     * @param player 玩家，可为 null（未进入世界时）
     * @return 物品 ID → 持有数量；玩家为 null 时返回空表
     */
    public static Map<String, Long> countAll(ClientPlayerEntity player) {
        Map<String, Long> owned = new HashMap<>();
        if (player == null) {
            return owned;
        }

        PlayerInventory inv = player.getInventory();
        // size() 已涵盖主背包、快捷栏、盔甲与副手
        for (int i = 0; i < inv.size(); i++) {
            addStack(owned, inv.getStack(i));
        }
        return owned;
    }

    /** 累加单个物品堆，并下潜统计其容器内含物。 */
    private static void addStack(Map<String, Long> owned, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        owned.merge(id, (long) stack.getCount(), Long::sum);

        addShulkerContents(owned, stack);
    }

    /**
     * 统计潜影盒（或其他带 BlockEntityTag 的容器物品）内部的物品。
     * 只下潜一层，内层不再递归。
     */
    private static void addShulkerContents(Map<String, Long> owned, ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(KEY_BLOCK_ENTITY_TAG, NbtElement.COMPOUND_TYPE)) {
            return;
        }
        NbtCompound blockEntityTag = nbt.getCompound(KEY_BLOCK_ENTITY_TAG);
        if (!blockEntityTag.contains(KEY_ITEMS, NbtElement.LIST_TYPE)) {
            return;
        }

        NbtList items = blockEntityTag.getList(KEY_ITEMS, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < items.size(); i++) {
            ItemStack inner = ItemStack.fromNbt(items.getCompound(i));
            if (inner.isEmpty()) {
                continue;
            }
            String innerId = Registries.ITEM.getId(inner.getItem()).toString();
            owned.merge(innerId, (long) inner.getCount(), Long::sum);
        }
    }
}
