package com.billy65536.qab.planner.region;

import java.util.*;

import com.billy65536.qab.integration.CsNavigationHelper;
import com.billy65536.qab.integration.CsNavigationHelper.ParsedPos;

/**
 * 一组 {@link Region} 的集合，并提供“不属于任何普通区域时自动归入的特殊区域”。
 * <p>
 * 典型用法：定义若干个命名区域，其余位置自动划入“未分配区域”。
 * </p>
 */
public class RegionDefinition {

    /** 未知维度常量，仅出现在 {@code pos == null} 时。 */
    public static final String UNKNOWN_DIMENSION = "unknown:unknown";

    private final List<Region> regions;
    private final Map<String, Region> unassignedRegion;

    /**
     * 用一组普通区域构造定义。
     *
     * @param regions 普通区域列表（不可变副本）
     */
    public RegionDefinition(List<Region> regions) {
        this.regions = List.copyOf(regions);
        // 未分配区域按维度懒创建（见 {@link #regionOf(ParsedPos)}），
        // 维度不同则实例不同，避免跨维度数据被同一个“全范围”区域错误吸收。
        this.unassignedRegion = new HashMap<>();
    }

    /**
     * 根据位置字符串查询所属区域。
     * <p>
     * 坐标格式为 {@code dimension(x,y,z)}（如 {@code "minecraft:overworld(12,65,-13)"}），
     * 由 {@link CsNavigationHelper#parsePosition} 解析；格式非法时视为未知维度。
     * 若坐标落在某个普通区域内（维度匹配），返回该区域；否则返回对应维度的“未分配”区域。
     *
     * @param position 位置字符串，不可为 {@code null}
     * @return 所属区域（永远不会为 {@code null}）
     */
    public Region regionOf(String position) {
        return regionOf(CsNavigationHelper.parsePosition(position));
    }

    /**
     * 根据已解析坐标查询所属区域。
     * <p>
     * 命中普通区域（维度匹配）返回该区域；否则返回按维度懒创建的“未分配”区域；
     * 解析失败（{@code pos == null}）归入未知维度的“未分配”区域。
     *
     * @param pos 已解析坐标，可为 {@code null}
     * @return 所属区域（永远不会为 {@code null}）
     */
    public Region regionOf(ParsedPos pos) {
        if (pos == null) {
            return unassignedRegion.computeIfAbsent(UNKNOWN_DIMENSION,
                    k -> Region.unassignedOf(UNKNOWN_DIMENSION));
        }
        // 遍历普通区域进行匹配
        for (Region region : regions) {
            if (region.contains(pos)) {
                return region;
            }
        }
        // 未匹配则归入该维度的“未分配”区域
        return unassignedRegion.computeIfAbsent(pos.dimensionId,
                k -> Region.unassignedOf(pos.dimensionId));
    }

    /**
     * 判断区域是否为构造时定义的普通区域。
     *
     * @param region 区域（通常来自 {@link #regionOf(ParsedPos)}）
     * @return {@code true} 为普通区域；{@code false} 为“未分配”区域
     */
    public boolean isAssignedRegion(Region region) {
        return regions.contains(region);
    }

    /**
     * 获取所有已定义的普通区域（不可变列表）。
     *
     * @return 普通区域列表
     */
    public List<Region> getRegions() {
        return regions;
    }
}