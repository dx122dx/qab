package com.billy65536.qab.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物清单生成配置（AutoConfig 驱动，JSON：{gameDir}/config/qab_schematic.json）。
 *
 * <p>收纳所有与 {@code /qab generate list}（购物清单生成）相关的可配置项，
 * 经 infrastructure 的 {@code /inf config get|set|reset|gui qab:schematic/...} 读写：</p>
 * <ul>
 *   <li><b>方块映射三表</b>（{@code unobtainable}/{@code irregular}/{@code composite}）：
 *       初始为空表，加载后与 {@link BlockMappingConfig} 的内置默认表按"内置默认 + 用户覆盖"语义合并，
 *       用户 JSON 中只需写差异；</li>
 *   <li><b>生成默认参数</b>（{@code name}/{@code description}/{@code redundancy}/{@code outName}/
 *       {@code multiplier}/{@code minCount}/{@code threshold}/{@code includeBlockEntities}/
 *       {@code deductInventory}/{@code rawId}/{@code excludes}/{@code sort}）：
 *       {@code /qab generate list} 命令行未指定的键取这里的默认值，命令行仍可覆盖；</li>
 *   <li><b>含水水桶开关</b>（{@code waterloggedCountsAsBucket}）：默认 false 保持现状——
 *       含水方块（{@code WATERLOGGED=true}）不额外计水桶；开启后每个含水方块额外计 1 水桶。</li>
 * </ul>
 *
 * <p>各参数语义与命令行键一致，见 {@code ListGenConfig} 的 javadoc。</p>
 */
@Config(name = "qab_schematic")
public class SchematicConfig implements ConfigData {

    /** 排序方式（与 {@code /qab generate list sort=...} 一致）。 */
    public enum SortMode {
        /** 按数量降序。 */
        COUNT,
        /** 按物品 ID 升序。 */
        ID
    }

    // ==================== 方块映射三表（默认空，与内置默认合并） ====================

    /** 无法购买的方块 ID 列表（流体、火、活塞头等）。空表 + 内置默认 = 内置默认。 */
    public List<String> unobtainable = new ArrayList<>();

    /** 不规则单映射：{@code 方块ID: 物品ID}。 */
    public Map<String, String> irregular = new LinkedHashMap<>();

    /** 组合方块：{@code 方块ID: [物品ID, 物品ID, ...]}。 */
    public Map<String, List<String>> composite = new LinkedHashMap<>();

    // ==================== 生成默认参数 ====================

    /** 清单名称。null = 使用原理图文件名。 */
    public String name;

    /** 清单描述。null = 自动生成。 */
    public String description;

    /** 冗余量（每项额外购买数量）。null = 0。 */
    public Integer redundancy;

    /** 输出文件名（不含扩展名）。null = 与 name 相同。 */
    public String outName;

    /** 数量倍率。null = 1.0。 */
    public Double multiplier;

    /** 单项最小数量（提升）。null = 不限制。 */
    public Integer minCount;

    /** 单项数量阈值（丢弃）。null = 1。 */
    public Integer threshold;

    /** 是否统计容器方块实体内部存放的物品。null = false。 */
    public Boolean includeBlockEntities;

    /** 是否扣除玩家背包现有物品。null = false。 */
    public Boolean deductInventory;

    /** 是否保留原始方块 ID（不做方块→物品映射）。null = false。 */
    public Boolean rawId;

    /** 排除的方块 ID 模式列表。空表示不排除。 */
    public List<String> excludes = new ArrayList<>();

    /** 排序方式。null = COUNT。 */
    public SortMode sort;

    // ==================== 含水处理 ====================

    /** 含水方块（WATERLOGGED=true）是否额外计 1 水桶。默认 false 保持现状。 */
    public boolean waterloggedCountsAsBucket = false;

    /**
     * 修复反序列化后可能出现的 null / 空白表项与非法数值。
     *
     * <p>AutoConfig 加载配置后回调；Gson 反序列化不经过字段初始值，
     * 集合字段可能为 null，必须在此兜底并剔除空白项。</p>
     */
    @Override
    public void validatePostLoad() {
        if (unobtainable == null) {
            unobtainable = new ArrayList<>();
        } else {
            unobtainable.removeIf(s -> s == null || s.isBlank());
        }

        if (irregular == null) {
            irregular = new LinkedHashMap<>();
        } else {
            irregular.entrySet().removeIf(e -> e.getKey() == null || e.getKey().isBlank()
                    || e.getValue() == null || e.getValue().isBlank());
        }

        if (composite == null) {
            composite = new LinkedHashMap<>();
        } else {
            composite.entrySet().removeIf(e -> e.getKey() == null || e.getKey().isBlank()
                    || e.getValue() == null || e.getValue().size() < 2
                    || e.getValue().get(0) == null || e.getValue().get(1) == null
                    || e.getValue().get(0).isBlank() || e.getValue().get(1).isBlank());
        }

        if (excludes == null) {
            excludes = new ArrayList<>();
        } else {
            excludes.removeIf(s -> s == null || s.isBlank());
        }

        if (redundancy != null && redundancy < 0) redundancy = null;
        if (multiplier != null && !(multiplier > 0)) multiplier = null;
        if (minCount != null && minCount < 0) minCount = null;
        if (threshold != null && threshold < 0) threshold = null;
    }
}
