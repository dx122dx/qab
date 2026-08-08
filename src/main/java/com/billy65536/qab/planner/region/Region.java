package com.billy65536.qab.planner.region;

import java.util.Objects;

import com.billy65536.qab.integration.CsNavigationHelper.ParsedPos;

/**
 * 表示一个三维空间区域（轴对齐长方体）。
 * <p>
 * 使用 Java {@code record} 自动生成构造器、访问器、{@code equals}、{@code hashCode} 和 {@code toString}。
 * 记录组件：两个对角顶点坐标（自动归一化为最小/最大坐标）以及维度标识。
 * </p>
 *
 * @param minX 区域在 X 轴的最小坐标（包含）
 * @param minY 区域在 Y 轴的最小坐标（包含）
 * @param minZ 区域在 Z 轴的最小坐标（包含）
 * @param maxX 区域在 X 轴的最大坐标（包含）
 * @param maxY 区域在 Y 轴的最大坐标（包含）
 * @param maxZ 区域在 Z 轴的最大坐标（包含）
 * @param dimension 区域所属维度（如 {@code "minecraft:overworld"}）
 */
public record Region(int minX, int minY, int minZ,
                     int maxX, int maxY, int maxZ,
                     String dimension) {

    /**
     * 紧凑构造器，确保 min 坐标 ≤ max 坐标。
     */
    public Region {
        if (minX > maxX) {
            int tmp = minX;
            minX = maxX;
            maxX = tmp;
        }
        if (minY > maxY) {
            int tmp = minY;
            minY = maxY;
            maxY = tmp;
        }
        if (minZ > maxZ) {
            int tmp = minZ;
            minZ = maxZ;
            maxZ = tmp;
        }
        Objects.requireNonNull(dimension, "dimension must not be null");
    }

    /**
     * 根据两个任意顶点创建一个区域，自动归一化坐标。
     *
     * @param x1 第一个顶点的 X 坐标
     * @param y1 第一个顶点的 Y 坐标
     * @param z1 第一个顶点的 Z 坐标
     * @param x2 第二个顶点的 X 坐标
     * @param y2 第二个顶点的 Y 坐标
     * @param z2 第二个顶点的 Z 坐标
     * @param dimensionId 区域所属维度
     * @return 归一化后的 Region 实例
     */
    public static Region of(int x1, int y1, int z1,
                            int x2, int y2, int z2,
                            String dimensionId) {
        return new Region(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                          Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                          dimensionId);
    }

    public static Region unassignedOf(String dimensionId) {
        return Region.of(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                         Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                         dimensionId);
    }

    /**
     * 判断给定坐标和维度是否在该区域内。
     *
     * @param pos 坐标
     * @return 若坐标在区域内且维度匹配，返回 {@code true}
     */
    public boolean contains(ParsedPos pos) {
        return this.dimension.equals(pos.dimensionId)
               && pos.x >= minX && pos.x <= maxX
               && pos.y >= minY && pos.y <= maxY
               && pos.z >= minZ && pos.z <= maxZ;
    }

    /**
     * 获取该区域在 XZ 平面上的中心点 X 坐标（用于区域间 TSP 排序）。
     *
     * @return 中心 X 坐标（可能带小数）
     */
    public double getCenterX() {
        return (minX + maxX) / 2.0;
    }

    /**
     * 获取该区域在 XZ 平面上的中心点 Z 坐标（用于区域间 TSP 排序）。
     *
     * @return 中心 Z 坐标（可能带小数）
     */
    public double getCenterZ() {
        return (minZ + maxZ) / 2.0;
    }
}