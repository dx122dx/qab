package com.billy65536.qab.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 购物清单生成配置：由 {@code /qab generate list <file> [config...]} 的命令行
 * {@code key=value} 串解析而来，用于调整生成结果的属性。
 *
 * <p>所有字段为 {@code null} 表示使用生成器内建默认值。风格参考 Chunk Scanner
 * 的 {@code TaskConfig}：空格分隔的 {@code key=value}，未知键与非法值仅警告不中断。
 *
 * <p>支持的键：
 * <ul>
 *   <li>{@code name} —— 清单名称（默认取原理图文件名）</li>
 *   <li>{@code desc} —— 清单描述（默认自动生成）</li>
 *   <li>{@code redundancy} —— 冗余量（每项额外购买数量），默认 0</li>
 *   <li>{@code out} —— 输出文件名（不含 .json），默认与 name 相同</li>
 *   <li>{@code multiplier} —— 数量倍率，对统计结果整体乘以该值，默认 1.0</li>
 *   <li>{@code min} —— 单项最小数量，低于该值的条目被提升到该值，默认不限制</li>
 *   <li>{@code threshold} —— 单项最小数量阈值，低于该值的条目被丢弃，默认 1</li>
 *   <li>{@code blockEntity} —— 是否把方块实体计入统计（true/false），默认 false</li>
 *   <li>{@code rawId} —— 是否保留原始方块 ID 而不转换为可购买物品 ID，默认 false。
 *       默认会把 {@code wall_torch} 之类的特殊形式映射为 {@code torch}；
 *       开启后按方块 ID 原样输出（通常仅用于调试）</li>
 *   <li>{@code exclude} —— 排除的方块 ID，逗号分隔，可多次出现；
 *       支持不带命名空间的简写与 {@code *} 后缀通配（如 {@code *_wall_sign}）</li>
 *   <li>{@code sort} —— 排序方式：{@code count}（数量降序，默认）/ {@code id}（ID 升序）</li>
 * </ul>
 *
 * <p>示例：{@code name=我的房子 redundancy=64 multiplier=2 exclude=air,*_sign sort=id}
 */
public class ListGenConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab/list-gen-config");

    /** 排序方式。 */
    public enum SortMode {
        /** 按数量降序。 */
        COUNT,
        /** 按物品 ID 升序。 */
        ID
    }

    /** 清单名称。null = 使用原理图文件名。 */
    public String name;

    /** 清单描述。null = 自动生成。 */
    public String description;

    /** 冗余量。null = 0。 */
    public Integer redundancy;

    /** 输出文件名（不含扩展名）。null = 与 name 相同。 */
    public String outName;

    /** 数量倍率。null = 1.0。 */
    public Double multiplier;

    /** 单项最小数量（提升）。null = 不限制。 */
    public Integer minCount;

    /** 单项数量阈值（丢弃）。null = 1。 */
    public Integer threshold;

    /** 是否统计方块实体。null = false。 */
    public Boolean includeBlockEntities;

    /** 是否保留原始方块 ID（不做方块→物品映射）。null = false。 */
    public Boolean rawId;

    /** 排除的方块 ID 模式列表。空表示不排除。 */
    public final List<String> excludes = new ArrayList<>();

    /** 排序方式。null = COUNT。 */
    public SortMode sort;

    /** 解析过程中产生的告警，供命令层回显给玩家。 */
    public final List<String> warnings = new ArrayList<>();

    /** 创建一个空配置（全部使用默认值）。 */
    public ListGenConfig() {}

    /**
     * 从 {@code key=value} 字符串解析配置。
     *
     * @param configStr 空格分隔的 key=value 串，可为 null 或空
     * @return 解析结果，永不为 null（空输入返回全默认配置）
     */
    public static ListGenConfig parse(String configStr) {
        ListGenConfig config = new ListGenConfig();
        if (configStr == null || configStr.isBlank()) {
            return config;
        }

        for (String part : configStr.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                config.warn("Malformed config token (expected key=value): " + part);
                continue;
            }

            String key = kv[0].toLowerCase(Locale.ROOT);
            String value = kv[1];

            try {
                switch (key) {
                    case "name" -> config.name = unquote(value);
                    case "desc", "description" -> config.description = unquote(value);
                    case "redundancy" -> config.redundancy = requireNonNegative(key, Integer.parseInt(value));
                    case "out", "output" -> config.outName = unquote(value);
                    case "multiplier", "mult" -> config.multiplier = requirePositive(key, Double.parseDouble(value));
                    case "min" -> config.minCount = requireNonNegative(key, Integer.parseInt(value));
                    case "threshold" -> config.threshold = requireNonNegative(key, Integer.parseInt(value));
                    case "blockentity", "blockentities" -> config.includeBlockEntities = parseBoolean(key, value);
                    case "rawid", "raw" -> config.rawId = parseBoolean(key, value);
                    case "exclude", "excludes" -> config.addExcludes(value);
                    case "sort" -> config.sort = parseSort(value);
                    default -> config.warn("Unknown config key: " + key);
                }
            } catch (NumberFormatException e) {
                config.warn("Invalid numeric value for " + key + ": " + value);
            } catch (IllegalArgumentException e) {
                config.warn(e.getMessage());
            }
        }
        return config;
    }

    private void addExcludes(String value) {
        for (String raw : unquote(value).split(",")) {
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) {
                excludes.add(s);
            }
        }
    }

    private void warn(String message) {
        warnings.add(message);
        LOGGER.warn("[generate list] {}", message);
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int requireNonNegative(String key, int v) {
        if (v < 0) throw new IllegalArgumentException("Value for " + key + " must be >= 0: " + v);
        return v;
    }

    private static double requirePositive(String key, double v) {
        if (!(v > 0)) throw new IllegalArgumentException("Value for " + key + " must be > 0: " + v);
        return v;
    }

    private static boolean parseBoolean(String key, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean for " + key + ": " + value);
        };
    }

    private static SortMode parseSort(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "count", "amount" -> SortMode.COUNT;
            case "id", "name", "alpha" -> SortMode.ID;
            default -> throw new IllegalArgumentException("Invalid sort mode: " + value + " (expected count|id)");
        };
    }

    // ---- effective getters（应用默认值） ----

    public int redundancyOrDefault() {
        return redundancy == null ? 0 : redundancy;
    }

    public double multiplierOrDefault() {
        return multiplier == null ? 1.0 : multiplier;
    }

    public int minCountOrDefault() {
        return minCount == null ? 0 : minCount;
    }

    public int thresholdOrDefault() {
        return threshold == null ? 1 : threshold;
    }

    public boolean includeBlockEntitiesOrDefault() {
        return includeBlockEntities != null && includeBlockEntities;
    }

    public boolean rawIdOrDefault() {
        return rawId != null && rawId;
    }

    public SortMode sortOrDefault() {
        return sort == null ? SortMode.COUNT : sort;
    }

    /** 生成配置说明字符串（紧凑单行，用于聊天消息）。 */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append("name=").append(name).append(' ');
        if (description != null) sb.append("desc=").append(description).append(' ');
        if (redundancy != null) sb.append("redundancy=").append(redundancy).append(' ');
        if (outName != null) sb.append("out=").append(outName).append(' ');
        if (multiplier != null) sb.append("multiplier=").append(multiplier).append(' ');
        if (minCount != null) sb.append("min=").append(minCount).append(' ');
        if (threshold != null) sb.append("threshold=").append(threshold).append(' ');
        if (includeBlockEntities != null) sb.append("blockEntity=").append(includeBlockEntities).append(' ');
        if (rawId != null) sb.append("rawId=").append(rawId).append(' ');
        if (!excludes.isEmpty()) sb.append("exclude=").append(String.join(",", excludes)).append(' ');
        if (sort != null) sb.append("sort=").append(sort.name().toLowerCase(Locale.ROOT)).append(' ');
        return sb.toString().trim();
    }
}
