package com.billy65536.qab.planner;

import java.util.*;
import java.util.stream.Collectors;

import com.billy65536.qab.planner.model.*;

/**
* 核心购物规划器，根据购物清单和导出的QShop数据生成最优购买计划。
* <p>算法：
* <ol>
*   <li>对于购物清单中的每件商品，查找所有匹配的商店</li>
*   <li>按单价对匹配的商店进行排序（升序，最便宜的在前）</li>
*   <li>从最便宜的商店贪心分配所需数量 + 冗余量</li>
*   <li>分配分为 "count"（必需）和 "redundancy"（安全库存）</li>
* </ol>
*/
public class ShoppingPlanner {

    /**
     * 生成购物计划。
     *
     * @param list 购物清单
     * @param export 导出的QShop数据
     * @return 生成的购物计划
     */
    public static ShoppingPlan generatePlan(ShoppingList list, ShopExportData export) {
        ShoppingPlan plan = new ShoppingPlan();
        int redundancy = Math.max(0, list.getRedundancy());

        if (list.getItems() == null || list.getItems().isEmpty()) {
            return plan;
        }

        // 构建查找：itemId -> 销售模式商店条目列表
        Map<String, List<ShopExportEntry>> shopsByItem = buildShopIndex(export);

        for (ShoppingItem item : list.getItems()) {
            processItem(item, redundancy, shopsByItem, plan);
        }

        // 将总成本四舍五入到小数点后两位。
        plan.setTotalCost(Math.round(plan.getTotalCost() * 100.0) / 100.0);

        return plan;
    }

    /**
     * 构建从物品ID到售卖模式商店条目列表的索引。
     */
    private static Map<String, List<ShopExportEntry>> buildShopIndex(ShopExportData export) {
        Map<String, List<ShopExportEntry>> index = new HashMap<>();
        if (export.getEntries() == null) return index;

        for (ShopExportEntry entry : export.getEntries()) {
            if (!entry.isSellMode()) continue;
            if (entry.getItemId() == null || entry.getItemId().isEmpty()) continue;
            index.computeIfAbsent(entry.getItemId(), k -> new ArrayList<>()).add(entry);
        }
        return index;
    }

    /**
     * 处理单个购物清单项目：查找匹配的商店，分配购买。
     */
    private static void processItem(ShoppingItem item, int redundancy,
                                     Map<String, List<ShopExportEntry>> shopsByItem,
                                     ShoppingPlan plan) {
        String itemId = item.getId();
        List<ShopExportEntry> candidates = shopsByItem.getOrDefault(itemId, Collections.emptyList());

        // 按匹配条件筛选候选
        List<ShopExportEntry> matched = filterMatchingShops(item, candidates);

        // 按价格降序升序排序
        matched.sort(Comparator.comparingInt(ShopExportEntry::getPrice));

        int needed = item.getCount() + redundancy;
        int allocatedCount = 0;
        int allocatedRedundancy = 0;

        for (ShopExportEntry shop : matched) {
            if (needed <= 0) break;

            int available = shop.getAvailableQuantity(needed);
            if (available <= 0) continue;

            int take = Math.min(available, needed);

            // 分配：首先满足 count ，然后满足 redundancy
            int takeCount = 0;
            int takeRedundancy = 0;
            int remainingCount = item.getCount() - allocatedCount;
            if (remainingCount > 0) {
                takeCount = Math.min(take, remainingCount);
                take -= takeCount;
                allocatedCount += takeCount;
            }
            if (take > 0) {
                takeRedundancy = take;
                allocatedRedundancy += takeRedundancy;
            }

            needed -= (takeCount + takeRedundancy);

            if (takeCount > 0 || takeRedundancy > 0) {
                // itemId 用于自动购买时查询堆叠上限做背包容量预判（格式版本 2 起必填）
                plan.addPlanEntry(new PlanEntry(shop.getPositionString(), shop.getItemId(),
                        takeCount, takeRedundancy));
                plan.addCost(shop.getRealPrice() * (takeCount + takeRedundancy));
            }
        }

        // 检查 count 是否完全满足
        if (allocatedCount < item.getCount()) {
            int remaining = item.getCount() - allocatedCount;
            ShoppingItem failItem = cloneItem(item);
            failItem.setCount(remaining);
            plan.addFailed(new FailedWarnEntry(failItem, remaining, 0));
        }

        // 检查 redundancy 是否已完全满足。
        int unfulfilledRedundancy = redundancy - allocatedRedundancy;
        if (allocatedCount >= item.getCount() && unfulfilledRedundancy > 0) {
            ShoppingItem warnItem = cloneItem(item);
            plan.addWarn(new FailedWarnEntry(warnItem, 0, unfulfilledRedundancy));
        }
    }

    /**
     * 筛选符合购物物品条件的商店条目
     * （itemId、附魔、matchNbt、maxAffordable）。
     */
    private static List<ShopExportEntry> filterMatchingShops(ShoppingItem item,
                                                              List<ShopExportEntry> candidates) {
        return candidates.stream()
                .filter(shop -> matchesItem(item, shop))
                .collect(Collectors.toList());
    }

    /**
     * 检查商店条目是否符合购物商品的要求。
     */
    private static boolean matchesItem(ShoppingItem item, ShopExportEntry shop) {
        // Item ID必须匹配
        if (!item.getId().equals(shop.getItemId())) {
            return false;
        }

        // 检查附魔要求
        if (item.hasEnchant()) {
            if (!SnbtParser.matchesEnchantments(shop.getDetailNbtString(), item.getEnchant())) {
                return false;
            }
        }

        // 检查nbt匹配
        if (item.hasMatchNbt()) {
            if (!SnbtParser.matchesNbt(shop.getDetailNbtString(), item.getMatchNbt())) {
                return false;
            }
        }

        // 查看最高可承受价格
        if (item.hasMaxAffordable()) {
            if (shop.getRealPrice() > item.getMaxAffordable()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 创建一个ShoppingItem的浅拷贝，用于失败/警告条目中。
     */
    private static ShoppingItem cloneItem(ShoppingItem original) {
        ShoppingItem clone = new ShoppingItem();
        clone.setId(original.getId());
        clone.setCount(original.getCount());
        clone.setEnchant(original.getEnchant());
        clone.setMatchNbt(original.getMatchNbt());
        clone.setMaxAffordable(original.getMaxAffordable());
        return clone;
    }
}
