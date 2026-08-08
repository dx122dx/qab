package com.billy65536.qab.planner.region;

import com.billy65536.qab.integration.CsNavigationHelper.ParsedPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一组命名区域的集合，可直接用 Gson 序列化 / 反序列化（无需额外的 DTO）。
 * <p>
 * 内存中以 {@code Map<String, Region>} 保存，key 为区域名；坐标为世界方块坐标，
 * 维度标识格式同 {@code dimension(x,y,z)}（见 {@code CsNavigationHelper}）。
 * 典型用法：定义若干个命名区域，购物规划时按位置归入不同区域做分组 + TSP 排序。
 */
public class RegionTable {

    /** 未知维度常量，仅作为兜底（当前数据模型下不会主动写入）。 */
    public static final String UNKNOWN_DIMENSION = "unknown:unknown";

    /** 命名区域表；保持插入顺序便于渲染与排查。 */
    private Map<String, Region> regions = new LinkedHashMap<>();

    public RegionTable() {
    }

    public RegionTable(Map<String, Region> regions) {
        if (regions != null) {
            this.regions.putAll(regions);
        }
    }

    /**
     * 根据已解析坐标查询所属区域。
     *
     * <p>遍历所有命名区域，命中（维度匹配且坐标落入范围）返回该区域；否则返回 {@code null}
     * （调用方应把 {@code null} 视为"未分配"，原序追加到计划末尾）。</p>
     *
     * @param pos 已解析坐标，可为 {@code null}
     * @return 所属区域；未命中或坐标为 {@code null} 时返回 {@code null}
     */
    public Region regionOf(ParsedPos pos) {
        if (pos == null) return null;
        for (Region region : regions.values()) {
            if (region.contains(pos)) return region;
        }
        return null;
    }

    /**
     * 判断区域是否为本表中的命名区域（区别于"未分配"的兜底区域）。
     *
     * @param region 区域（通常来自 {@link #regionOf(ParsedPos)}）
     * @return {@code true} 为本表命名区域；{@code null} 或不在表中返回 {@code false}
     */
    public boolean isAssignedRegion(Region region) {
        return region != null && regions.containsValue(region);
    }

    /** 获取所有命名区域（只读视图）。 */
    public java.util.Collection<Region> getRegions() {
        return regions.values();
    }

    // ==================== 增删改查 ====================

    /** 新增 / 覆盖一个命名区域。 */
    public void add(String name, Region region) {
        regions.put(name, region);
    }

    /** 按名获取区域，不存在返回 {@code null}。 */
    public Region get(String name) {
        return regions.get(name);
    }

    /** 移除命名区域，返回是否确实存在该区域。 */
    public boolean remove(String name) {
        return regions.remove(name) != null;
    }

    /** 是否含指定名称的区域。 */
    public boolean containsName(String name) {
        return regions.containsKey(name);
    }

    /** 所有区域名（保持插入顺序）。 */
    public Set<String> names() {
        return regions.keySet();
    }

    /** 区域数量。 */
    public int size() {
        return regions.size();
    }

    /** 是否为空表。 */
    public boolean isEmpty() {
        return regions.isEmpty();
    }
}
