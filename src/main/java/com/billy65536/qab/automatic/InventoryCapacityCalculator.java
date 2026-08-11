package com.billy65536.qab.automatic;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 背包容量计算：判断「还能装下多少个指定物品」。
 *
 * <p>QShop 在玩家背包空间不足时会<b>拒绝发货</b>，且这是纯服务端行为，
 * 客户端无法从响应可靠地检测失败。因此只能在下单<b>之前</b>算准容量，
 * 买不下就少买点（部分购买），剩余量稍后补齐。</p>
 *
 * <h3>为什么不能统一按 64 估算</h3>
 * <p>不同物品堆叠上限差异极大：末影珍珠 16、盔甲/桶/床 1、多数方块 64。
 * 统一按 64 算会严重<b>低估</b>格子需求 —— 例如买 16 个桶，按 64 算只需 1 格，
 * 实际要 16 格，结果仍会被 QShop 拒发。必须查 {@link Item#getMaxCount()}。</p>
 *
 * <h3>容量构成</h3>
 * <p>可容纳量 = 空格子数 × 堆叠上限 + 已有同类物品堆的剩余空间。
 * 后者不可忽略：背包里已有一堆 32 个石头时，那一格还能再塞 32 个。</p>
 *
 * <p>只统计<b>主背包 slot 9..35</b>（27 格）。快捷栏 0-8 留给玩家放工具/武器，
 * 不作为购买容量，也不参与存货搬运 —— 与 auto-sail 的做法一致。</p>
 */
public final class InventoryCapacityCalculator {

    /** 主背包起始 slot（跳过快捷栏 0-8）。 */
    public static final int MAIN_INV_START = 9;
    /** 主背包结束 slot（含）。 */
    public static final int MAIN_INV_END = 35;
    /** 主背包总格数。 */
    public static final int MAIN_INV_SIZE = MAIN_INV_END - MAIN_INV_START + 1;

    private InventoryCapacityCalculator() {
    }

    /**
     * 解析物品 ID 为 {@link Item}。
     *
     * @param itemId 形如 {@code minecraft:stone}
     * @return 对应物品；ID 非法或未注册时返回 null
     */
    public static Item resolveItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        // 未注册的 ID 会返回 AIR 兜底值，需显式排除
        if (!Registries.ITEM.containsId(id)) return null;
        return Registries.ITEM.get(id);
    }

    /**
     * 计算主背包还能容纳多少个指定物品。
     *
     * @param player       玩家
     * @param item         目标物品
     * @param reserveSlots 额外预留的空格数（不计入可用容量），给服务器赠品/找零留余地
     * @return 可容纳的物品个数（&gt;= 0）
     */
    public static int capacityFor(ClientPlayerEntity player, Item item, int reserveSlots) {
        if (player == null || item == null) return 0;

        PlayerInventory inv = player.getInventory();
        int maxStack = Math.max(1, item.getMaxCount());

        int emptySlots = 0;
        int partialRoom = 0;

        for (int i = MAIN_INV_START; i <= MAIN_INV_END; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                // 同类物品未满堆：该格还能再塞一些。
                // 注意用 stack.getMaxCount() 而非 item.getMaxCount()：
                // 带 NBT 的物品实例堆叠上限可能被改写。
                partialRoom += stack.getMaxCount() - stack.getCount();
            }
        }

        int usableEmpty = Math.max(0, emptySlots - Math.max(0, reserveSlots));
        return usableEmpty * maxStack + partialRoom;
    }

    /**
     * 计算主背包还能容纳多少个指定物品（按物品 ID）。
     *
     * @param player       玩家
     * @param itemId       物品 ID
     * @param reserveSlots 额外预留的空格数
     * @return 可容纳个数；物品 ID 无法解析时返回 -1（调用方需区分「装不下」与「算不出」）
     */
    public static int capacityFor(ClientPlayerEntity player, String itemId, int reserveSlots) {
        Item item = resolveItem(itemId);
        if (item == null) return -1;
        return capacityFor(player, item, reserveSlots);
    }

    /**
     * 主背包剩余空格数（不含快捷栏）。
     *
     * @param player 玩家
     * @return 空格子数量
     */
    public static int emptySlots(ClientPlayerEntity player) {
        if (player == null) return 0;
        PlayerInventory inv = player.getInventory();
        int empty = 0;
        for (int i = MAIN_INV_START; i <= MAIN_INV_END; i++) {
            if (inv.getStack(i).isEmpty()) empty++;
        }
        return empty;
    }

    /**
     * 主背包是否已无空格。
     *
     * @param player 玩家
     * @return 27 格全部被占用时返回 true
     */
    public static boolean isMainInventoryFull(ClientPlayerEntity player) {
        return emptySlots(player) == 0;
    }

    /**
     * 统计主背包（slot 9..35）中指定物品的总数量。
     *
     * <p>用于自动购买结算后核对「背包确实增多了多少」。只统计主背包，
     * 与容量预判口径一致（快捷栏不参与购买容量，也不参与库存核对）。</p>
     *
     * @param player 玩家
     * @param item   目标物品
     * @return 主背包内该物品的总个数（多堆相加）
     */
    public static int countItems(ClientPlayerEntity player, Item item) {
        if (player == null || item == null) return 0;
        PlayerInventory inv = player.getInventory();
        int total = 0;
        for (int i = MAIN_INV_START; i <= MAIN_INV_END; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 统计主背包中指定物品 ID 的总数量（按 ID 查找）。
     *
     * @param player 玩家
     * @param itemId 物品 ID，形如 {@code minecraft:stone}
     * @return 总个数；物品 ID 无法解析时返回 -1
     */
    public static int countItems(ClientPlayerEntity player, String itemId) {
        Item item = resolveItem(itemId);
        if (item == null) return -1;
        return countItems(player, item);
    }
}
