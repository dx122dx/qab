package com.billy65536.qab.planner.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 区域表（{@link RegionTable}）的运行时持有者与持久化门面。
 * <p>
 * 以静态工具类形式存在：保存「当前打开的区域表」与「其名称」。玩家未主动打开任何表时，
 * 首次访问会自动创建一个名为 {@code default} 的空表（满足"未打开选区表时自动创建"）。
 * 区域表以 JSON 持久化于 {@code <gameDir>/qab/region/<name>.json}，
 * 序列化直接走 Gson + {@link RegionTable}（不额外写 DTO）。
 */
public final class RegionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab.region.manager");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path REGION_DIR =
            FabricLoader.getInstance().getGameDir().resolve("qab").resolve("region");

    private static final String DEFAULT_TABLE = "default";

    /** 当前打开的区域表（null 表示尚未打开）。 */
    private static RegionTable currentTable;
    /** 当前打开区域表的名称（用于保存路径与反馈）。 */
    private static String currentTableName;

    private RegionManager() {
    }

    /**
     * 确保已有一个区域表：若尚未打开则自动创建名为 {@code default} 的空表。
     *
     * <p>调用方（渲染器、命令）在需要区域表时一律经由此方法，避免到处判空。</p>
     */
    private static void ensureTable() {
        if (currentTable == null) {
            currentTable = new RegionTable();
            currentTableName = DEFAULT_TABLE;
            LOGGER.info("Auto-created region table '{}' (no table was open).", currentTableName);
        }
    }

    /** 获取当前区域表（必要时自动创建）。 */
    public static RegionTable getCurrentTable() {
        ensureTable();
        return currentTable;
    }

    /** 当前区域表名称（必要时自动创建）。 */
    public static String getCurrentTableName() {
        ensureTable();
        return currentTableName;
    }

    /**
     * 打开（或新建）指定名称的区域表。
     *
     * <p>文件存在则加载；不存在则自动创建一个同名空表（满足"未打开时自动创建"）。
     * 加载失败回退为空表并记录日志。</p>
     *
     * @param name 区域表名（用于文件名与反馈）
     * @return {@code true} 表示成功从磁盘加载了已存在的表；{@code false} 表示新建了空表
     */
    public static boolean open(String name) {
        if (name == null || name.isBlank()) return false;
        Path file = REGION_DIR.resolve(sanitize(name) + ".json");
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                RegionTable table = GSON.fromJson(json, RegionTable.class);
                if (table == null) table = new RegionTable();
                currentTable = table;
                currentTableName = name;
                LOGGER.info("Opened region table '{}' ({} region(s)).", name, table.size());
                return true;
            } catch (Exception e) {
                LOGGER.warn("Failed to load region table '{}': {}", file, e.getMessage());
                return false;
            }
        }
        currentTable = new RegionTable();
        currentTableName = name;
        LOGGER.info("Region table '{}' not found, auto-created empty table.", name);
        return false;
    }

    /**
     * 将当前区域表持久化到磁盘。
     *
     * @return 成功返回 true；失败返回 false 并记录日志
     */
    public static boolean save() {
        ensureTable();
        try {
            Files.createDirectories(REGION_DIR);
            Path file = REGION_DIR.resolve(sanitize(currentTableName) + ".json");
            Files.writeString(file, GSON.toJson(currentTable), StandardCharsets.UTF_8);
            LOGGER.info("Saved region table '{}' ({} region(s)) to {}",
                    currentTableName, currentTable.size(), file);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to save region table '{}': {}", currentTableName, e.getMessage());
            return false;
        }
    }

    /**
     * 新增一个命名区域并立即持久化。
     *
     * @param name   区域名
     * @param region 区域（含两个对角顶点与维度）
     */
    public static void addRegion(String name, Region region) {
        if (name == null || name.isBlank() || region == null) return;
        ensureTable();
        currentTable.add(name, region);
        save();
    }

    /**
     * 移除一个命名区域（若存在）并立即持久化。
     *
     * @param name 区域名
     * @return 确实移除返回 true
     */
    public static boolean removeRegion(String name) {
        ensureTable();
        boolean removed = currentTable.remove(name);
        if (removed) save();
        return removed;
    }

    /** 去除文件名非法字符，避免写入失败或目录穿越。 */
    private static String sanitize(String name) {
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.isBlank() ? DEFAULT_TABLE : s;
    }
}
