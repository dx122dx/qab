package com.billy65536.qab.planner;

import java.util.*;
import java.util.stream.Collectors;

import com.billy65536.qab.integration.CsNavigationHelper;
import com.billy65536.qab.integration.CsNavigationHelper.ParsedPos;
import com.billy65536.qab.planner.model.*;
import com.billy65536.qab.planner.region.Region;
import com.billy65536.qab.planner.region.RegionTable;

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
     * @param regionTable 区域表，用于将坐标归入不同区域并获取区域中心点
     * @return 生成的购物计划
     */
    public static ShoppingPlan generatePlan(ShoppingList list, ShopExportData export,
                                            RegionTable regionTable) {
        ShoppingPlan plan = new ShoppingPlan();
        // 全局设置：固定冗余量、数量倍率、冗余率（百分比数值），负值一律按 0 处理
        int fixedRedundancy = Math.max(0, list.getRedundancy());
        double multiplier = Math.max(0.0, list.getMultiplier());
        double redundancyPercent = Math.max(0.0, list.getRedundancyPercent());

        if (list.getItems() == null || list.getItems().isEmpty()) {
            return plan;
        }

        Map<String, List<ShopExportEntry>> shopsByItem = buildShopIndex(export);

        for (ShoppingItem item : list.getItems()) {
            processItem(item, multiplier, redundancyPercent, fixedRedundancy, shopsByItem, plan);
        }

        // 将总成本四舍五入到小数点后两位。
        plan.setTotalCost(Math.round(plan.getTotalCost() * 100.0) / 100.0);

        if (regionTable != null && plan.getPlan() != null && !plan.getPlan().isEmpty()) {
            plan.setPlan(optimizePlanOrder(plan.getPlan(), regionTable));
        }

        return plan;
    }

    // ====================== 初始化 =======================

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

    // ====================== 初次规划 =======================

    /**
     * 处理单个购物清单项目：查找匹配的商店，分配购买。
     * <p>计算规则：需求 = round(需求数 × 倍率)，比率冗余 = round(需求 × 冗余率%)，
     * 每项冗余 = 比率冗余 + 固定冗余量，购买 = 需求 + 冗余。
     */
    private static void processItem(ShoppingItem item, double multiplier, double redundancyPercent,
                                    int fixedRedundancy,
                                    Map<String, List<ShopExportEntry>> shopsByItem,
                                    ShoppingPlan plan) {
        String itemId = item.getId();
        List<ShopExportEntry> candidates = shopsByItem.getOrDefault(itemId, Collections.emptyList());

        // 按匹配条件筛选候选
        List<ShopExportEntry> matched = filterMatchingShops(item, candidates);

        // 按价格升序排序（最便宜的在最前）
        matched.sort(Comparator.comparingInt(ShopExportEntry::getPrice));

        // 计算规则：需求 = round(需求数 × 倍率)，比率冗余 = round(需求 × 冗余率%)，
        // 每项冗余 = 比率冗余 + 固定冗余量，购买 = 需求 + 冗余
        int demand = (int) Math.round(item.getCount() * multiplier);
        int ratioRedundancy = (int) Math.round(demand * redundancyPercent / 100.0);
        int redundancyPerItem = ratioRedundancy + fixedRedundancy;
        int needed = demand + redundancyPerItem;
        int allocatedCount = 0;
        int allocatedRedundancy = 0;

        for (ShopExportEntry shop : matched) {
            if (needed <= 0) break;

            int available = shop.getAvailableQuantity(needed);
            if (available <= 0) continue;

            int take = Math.min(available, needed);

            // 分配：首先满足需求，然后满足冗余
            int takeCount = 0;
            int takeRedundancy = 0;
            int remainingCount = demand - allocatedCount;
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

        // 检查需求是否完全满足
        if (allocatedCount < demand) {
            int remaining = demand - allocatedCount;
            ShoppingItem failItem = cloneItem(item);
            failItem.setCount(remaining);
            plan.addFailed(new FailedWarnEntry(failItem, remaining, 0));
        }

        // 检查冗余是否已完全满足
        int unfulfilledRedundancy = redundancyPerItem - allocatedRedundancy;
        if (allocatedCount >= demand && unfulfilledRedundancy > 0) {
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

    // ====================== 二次规划 =======================

    /**
     * 对计划条目列表进行区域分组 + 双层 TSP 近似排序。
     *
     * @param entries   原始计划条目列表
     * @param regionDef 区域定义
     * @return 重新排序后的计划条目列表
     */
    private static List<PlanEntry> optimizePlanOrder(List<PlanEntry> entries,
                                                     RegionTable regionTable) {
        // 1. 预解析每个条目的坐标并按区域分组；
        //    解析失败或未命中任何命名区域的条目归入 unassigned，保持原序最后追加
        Map<Region, List<PlanEntry>> regionToEntries = new LinkedHashMap<>();
        Map<PlanEntry, ParsedPos> parsed = new HashMap<>();
        List<PlanEntry> unassignedEntries = new ArrayList<>();

        for (PlanEntry entry : entries) {
            ParsedPos pos = CsNavigationHelper.parsePosition(entry.getPosition());
            parsed.put(entry, pos);
            Region region = regionTable.regionOf(pos);
            if (regionTable.isAssignedRegion(region)) {
                regionToEntries.computeIfAbsent(region, k -> new ArrayList<>()).add(entry);
            } else {
                unassignedEntries.add(entry);
            }
        }

        // 2. 每个区域内，对条目按其坐标进行 TSP 近似排序
        for (Map.Entry<Region, List<PlanEntry>> entry : regionToEntries.entrySet()) {
            entry.setValue(tspSortByCoordinate(entry.getValue(), parsed));
        }

        // 3. 收集所有区域，以区域中心点为代表进行 TSP 排序，确定区域访问顺序
        List<Region> regions = new ArrayList<>(regionToEntries.keySet());
        if (regions.size() > 2) {
            regions = tspSortRegions(regions);
        }

        // 4. 拼接：区域按序在前，未分配条目保持原序追加在后
        List<PlanEntry> sortedPlan = new ArrayList<>();
        for (Region region : regions) {
            sortedPlan.addAll(regionToEntries.get(region));
        }
        sortedPlan.addAll(unassignedEntries);
        return sortedPlan;
    }

    /**
     * 对同一区域内的条目按坐标进行 TSP 近似排序（最近邻启发式）。
     * 起点选择坐标最小的点（先比 x 再比 z）。
     *
     * @param entries 区域内的条目（调用方保证其坐标已成功解析）
     * @param parsed  条目 -> 已解析坐标的映射
     */
    private static List<PlanEntry> tspSortByCoordinate(List<PlanEntry> entries,
                                                       Map<PlanEntry, ParsedPos> parsed) {
        int n = entries.size();
        if (n <= 2) return new ArrayList<>(entries);

        // 提取 XZ 坐标（MC 寻路以水平面为主，忽略高度）
        int[][] coords = new int[n][2];
        for (int i = 0; i < n; i++) {
            ParsedPos pos = parsed.get(entries.get(i));
            coords[i][0] = pos.x;
            coords[i][1] = pos.z;
        }

        // 找到起始点（最小 x，x 相同则最小 z）
        int start = 0;
        for (int i = 1; i < n; i++) {
            if (coords[i][0] < coords[start][0] ||
                (coords[i][0] == coords[start][0] && coords[i][1] < coords[start][1])) {
                start = i;
            }
        }

        boolean[] visited = new boolean[n];
        List<PlanEntry> result = new ArrayList<>(n);
        int current = start;

        for (int i = 0; i < n; i++) {
            visited[current] = true;
            result.add(entries.get(current));

            // 寻找距离当前点最近的未访问点
            int next = -1;
            long minDist = Long.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (!visited[j]) {
                    long dx = (long) coords[j][0] - coords[current][0];
                    long dz = (long) coords[j][1] - coords[current][1];
                    long dist = dx * dx + dz * dz;
                    if (dist < minDist) {
                        minDist = dist;
                        next = j;
                    }
                }
            }
            if (next == -1) break;
            current = next;
        }
        return result;
    }

    /**
     * 对区域列表按区域中心点进行 TSP 近似排序（最近邻启发式）。
     * 起点选择中心坐标最小的区域（先比 x 再比 z）。
     */
    private static List<Region> tspSortRegions(List<Region> regions) {
        int n = regions.size();
        if (n <= 2) return new ArrayList<>(regions);

        double[][] centers = new double[n][2];
        for (int i = 0; i < n; i++) {
            centers[i][0] = regions.get(i).getCenterX();
            centers[i][1] = regions.get(i).getCenterZ();
        }

        // 找起始区域
        int start = 0;
        for (int i = 1; i < n; i++) {
            if (centers[i][0] < centers[start][0] ||
                (centers[i][0] == centers[start][0] && centers[i][1] < centers[start][1])) {
                start = i;
            }
        }

        boolean[] visited = new boolean[n];
        List<Region> result = new ArrayList<>(n);
        int current = start;

        for (int i = 0; i < n; i++) {
            visited[current] = true;
            result.add(regions.get(current));

            int next = -1;
            double minDist = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (!visited[j]) {
                    double dx = centers[j][0] - centers[current][0];
                    double dz = centers[j][1] - centers[current][1];
                    double dist = dx * dx + dz * dz;
                    if (dist < minDist) {
                        minDist = dist;
                        next = j;
                    }
                }
            }
            if (next == -1) break;
            current = next;
        }
        return result;
    }
}
