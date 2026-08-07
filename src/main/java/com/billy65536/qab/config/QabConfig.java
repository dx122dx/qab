package com.billy65536.qab.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * QAB 运行时配置（JSON：{gameDir}/config/qab/qab.json）。
 *
 * <p>仅包含 QAB 自身需要的可配置项，不依赖 chunkscanner 的配置类。</p>
 *
 * <h3>购买相关</h3>
 * <ul>
 *   <li>{@code buyDelayMs}：到达告示牌后、发送购买命令前的等待毫秒数（默认 500）；</li>
 *   <li>{@code buyCommand}：到达后执行的购买命令模板，
 *       形如 {@code /qs amount {count}}，{@code {count}} 被替换为本次购买数量；</li>
     *   <li>{@code clickReachDist}：判定"准星可点击到告示牌"的最大距离（默认 4.0 方块）。
     *       默认 4.0 对应 MC 1.20.1 原版服务端方块交互距离上限约 4.5 格的安全余量；
     *       部分服务器/模组会修改该上限，可按实际情况调大，不做硬上限限制；</li>
 *   <li>{@code sightBlockedTimeoutTicks}：到店判定中视线被遮挡时的最大等待 tick 数（默认 60，
 *       超时后按"已到达"处理交由对准流程收尾，避免卡死）；</li>
 *   <li>{@code aimDegPerTick}：到店后转视角的每 tick 最大角度（默认 30°，值越小转头越"像人"）；</li>
 *   <li>{@code aimSettleTicks}：精确对准后、点击前的静置 tick 数（默认 2），
 *       给服务端留出接收新朝向的时间。</li>
 * </ul>
 *
 * <h3>存货（stash）相关</h3>
 * <p>QShop 在玩家背包空间不足时会<b>拒绝发货</b>，因此购买前必须预判容量，
 * 不足时先把背包里的东西存进箱子再回来买。</p>
 * <ul>
 *   <li>{@code stashEnabled}：是否启用自动存货（默认 true；关闭后容量不足只会告警并跳过）；</li>
 *   <li>{@code stashPositions}：存货箱坐标列表，格式 {@code dimension(x,y,z)}，
 *       与计划文件中的 position 同格式。箱子装满后按列表顺序<b>顺延</b>到下一个；</li>
 *   <li>{@code stashKeepItems}：存货时保留在背包里不搬走的物品 ID（工具、货币等）；</li>
 *   <li>{@code stashTransferDelayTicks}：每搬运一格之间的间隔 tick（默认 2，防止被服务端判定异常）；</li>
 *   <li>{@code stashReserveSlots}：容量预判时额外预留的空格数（默认 1，给服务器可能的赠品/找零留余地）。</li>
 * </ul>
 */
public final class QabConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** QAB 配置目录：{gameDir}/config/qab/。与 BlockMappingConfig 共用，作为单一路径来源。 */
    public static final Path CONFIG_DIR = FabricLoader.getInstance().getGameDir()
            .resolve("config").resolve("qab");

    /** 配置文件路径。 */
    public static final Path CONFIG_FILE = CONFIG_DIR.resolve("qab.json");

    private static final int DEFAULT_BUY_DELAY_MS = 500;
    private static final String DEFAULT_BUY_COMMAND = "/qs amount {count}";
    private static final double DEFAULT_CLICK_REACH_DIST = 4.0;
    private static final float DEFAULT_AIM_DEG_PER_TICK = 30.0F;
    private static final int DEFAULT_AIM_SETTLE_TICKS = 2;
    private static final int DEFAULT_SIGHT_BLOCKED_TIMEOUT_TICKS = 60;
    /** 视线被挡超时下限：太小会在绕路途中经过牌子附近时误判到达。 */
    private static final int MIN_SIGHT_BLOCKED_TIMEOUT_TICKS = 20;

    /** 转头速度下限：太小会让对准迟迟完不成，触发超时放弃。 */
    private static final float MIN_AIM_DEG_PER_TICK = 5.0F;
    /** 转头速度上限：180°/tick 等价于瞬间对准。 */
    private static final float MAX_AIM_DEG_PER_TICK = 180.0F;
    /** 静置 tick 上限：再久也没有意义，只会拖慢购买。 */
    private static final int MAX_AIM_SETTLE_TICKS = 40;

    private static final boolean DEFAULT_STASH_ENABLED = true;
    private static final int DEFAULT_STASH_TRANSFER_DELAY_TICKS = 2;
    private static final int DEFAULT_STASH_RESERVE_SLOTS = 1;

    /** 搬运间隔上限，避免用户填个巨大值让存货永远跑不完。 */
    private static final int MAX_STASH_TRANSFER_DELAY_TICKS = 100;
    /** 预留格子数上限：主背包共 27 格，留满就没法买东西了。 */
    private static final int MAX_STASH_RESERVE_SLOTS = 26;

    /** 到达后发送购买命令前的延时（毫秒）。 */
    private int buyDelayMs = DEFAULT_BUY_DELAY_MS;
    /** 购买命令模板，{@code {count}} 占位符替换为本次购买数量。 */
    private String buyCommand = DEFAULT_BUY_COMMAND;
    /** 准星可点击告示牌的最大距离（方块）。 */
    private double clickReachDist = DEFAULT_CLICK_REACH_DIST;
    /** 到店判定中视线被遮挡时的最大等待 tick 数；超时后按"已到达"处理交由对准流程收尾。 */
    private int sightBlockedTimeoutTicks = DEFAULT_SIGHT_BLOCKED_TIMEOUT_TICKS;
    /** 到店后转视角的每 tick 最大角度（度）。 */
    private float aimDegPerTick = DEFAULT_AIM_DEG_PER_TICK;
    /** 精确对准后、发起点击前的静置 tick 数。 */
    private int aimSettleTicks = DEFAULT_AIM_SETTLE_TICKS;

    /** 是否启用自动存货。 */
    private boolean stashEnabled = DEFAULT_STASH_ENABLED;
    /** 存货箱坐标列表，格式 {@code dimension(x,y,z)}，箱满时按顺序顺延。 */
    private List<String> stashPositions = new ArrayList<>();
    /** 存货时保留在背包中的物品 ID（不搬进箱子）。 */
    private List<String> stashKeepItems = new ArrayList<>();
    /** 每搬运一格之间的间隔 tick。 */
    private int stashTransferDelayTicks = DEFAULT_STASH_TRANSFER_DELAY_TICKS;
    /** 容量预判时额外预留的空格数。 */
    private int stashReserveSlots = DEFAULT_STASH_RESERVE_SLOTS;

    private QabConfig() {
    }

    /**
     * 从默认路径加载配置：{gameDir}/config/qab/qab.json。
     * 文件不存在时使用默认值；解析失败回退默认值并告警。
     *
     * @return 加载后的配置（永不返回 null）
     */
    public static QabConfig load() {
        QabConfig cfg = new QabConfig();
        if (!Files.exists(CONFIG_FILE)) {
            LOGGER.info("QAB config not found at {}, using defaults.", CONFIG_FILE);
            return cfg;
        }
        try {
            String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            QabConfig parsed = GSON.fromJson(json, QabConfig.class);
            if (parsed != null) {
                parsed.sanitize();
                cfg = parsed;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load QAB config from {}, using defaults: {}", CONFIG_FILE, e.getMessage());
        }
        return cfg;
    }

    /**
     * 修复反序列化后可能出现的非法值 / null 集合。
     *
     * <p>Gson 不会调用构造函数，字段初始值不生效，集合字段可能为 null，必须在此兜底。</p>
     */
    private void sanitize() {
        if (buyDelayMs < 0) buyDelayMs = DEFAULT_BUY_DELAY_MS;
        if (buyCommand == null || buyCommand.isBlank()) buyCommand = DEFAULT_BUY_COMMAND;
        if (clickReachDist <= 0) clickReachDist = DEFAULT_CLICK_REACH_DIST;
        if (sightBlockedTimeoutTicks < MIN_SIGHT_BLOCKED_TIMEOUT_TICKS) {
            sightBlockedTimeoutTicks = MIN_SIGHT_BLOCKED_TIMEOUT_TICKS;
        }

        if (aimDegPerTick < MIN_AIM_DEG_PER_TICK) {
            aimDegPerTick = aimDegPerTick <= 0 ? DEFAULT_AIM_DEG_PER_TICK : MIN_AIM_DEG_PER_TICK;
        } else if (aimDegPerTick > MAX_AIM_DEG_PER_TICK) {
            aimDegPerTick = MAX_AIM_DEG_PER_TICK;
        }
        if (aimSettleTicks < 0) {
            aimSettleTicks = DEFAULT_AIM_SETTLE_TICKS;
        } else if (aimSettleTicks > MAX_AIM_SETTLE_TICKS) {
            LOGGER.warn("aimSettleTicks={} too large, clamped to {}.",
                    aimSettleTicks, MAX_AIM_SETTLE_TICKS);
            aimSettleTicks = MAX_AIM_SETTLE_TICKS;
        }

        if (stashPositions == null) {
            stashPositions = new ArrayList<>();
        } else {
            stashPositions.removeIf(s -> s == null || s.isBlank());
        }
        if (stashKeepItems == null) {
            stashKeepItems = new ArrayList<>();
        } else {
            stashKeepItems.removeIf(s -> s == null || s.isBlank());
        }

        if (stashTransferDelayTicks < 0) {
            stashTransferDelayTicks = DEFAULT_STASH_TRANSFER_DELAY_TICKS;
        } else if (stashTransferDelayTicks > MAX_STASH_TRANSFER_DELAY_TICKS) {
            LOGGER.warn("stashTransferDelayTicks={} too large, clamped to {}.",
                    stashTransferDelayTicks, MAX_STASH_TRANSFER_DELAY_TICKS);
            stashTransferDelayTicks = MAX_STASH_TRANSFER_DELAY_TICKS;
        }

        if (stashReserveSlots < 0) {
            stashReserveSlots = DEFAULT_STASH_RESERVE_SLOTS;
        } else if (stashReserveSlots > MAX_STASH_RESERVE_SLOTS) {
            LOGGER.warn("stashReserveSlots={} too large, clamped to {}.",
                    stashReserveSlots, MAX_STASH_RESERVE_SLOTS);
            stashReserveSlots = MAX_STASH_RESERVE_SLOTS;
        }
    }

    /**
     * 将当前配置写回 {@link #CONFIG_FILE}。
     *
     * <p>供 {@code /qab stash add|remove} 持久化点位改动。</p>
     *
     * @return 成功写入返回 true；失败返回 false 并记录日志
     */
    public boolean save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_FILE, GSON.toJson(this), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to save QAB config to {}: {}", CONFIG_FILE, e.getMessage());
            return false;
        }
    }

    public int getBuyDelayMs() {
        return buyDelayMs;
    }

    public String getBuyCommand() {
        return buyCommand;
    }

    public double getClickReachDist() {
        return clickReachDist;
    }

    /** 到店判定中视线被遮挡时的最大等待 tick 数。 */
    public int getSightBlockedTimeoutTicks() {
        return sightBlockedTimeoutTicks;
    }

    /** 到店后转视角的每 tick 最大角度（度）。 */
    public float getAimDegPerTick() {
        return aimDegPerTick;
    }

    /** 精确对准后、发起点击前的静置 tick 数。 */
    public int getAimSettleTicks() {
        return aimSettleTicks;
    }

    public boolean isStashEnabled() {
        return stashEnabled;
    }

    /** 存货点列表（只读视图）。 */
    public List<String> getStashPositions() {
        return Collections.unmodifiableList(stashPositions);
    }

    /** 存货时保留在背包中的物品 ID（只读视图）。 */
    public List<String> getStashKeepItems() {
        return Collections.unmodifiableList(stashKeepItems);
    }

    public int getStashTransferDelayTicks() {
        return stashTransferDelayTicks;
    }

    public int getStashReserveSlots() {
        return stashReserveSlots;
    }

    /**
     * 追加一个存货点。
     *
     * @param position 位置字符串 {@code dimension(x,y,z)}
     * @return 成功追加返回 true；已存在相同点位返回 false
     */
    public boolean addStashPosition(String position) {
        if (position == null || position.isBlank()) return false;
        if (stashPositions.contains(position)) return false;
        stashPositions.add(position);
        return true;
    }

    /**
     * 移除一个存货点。
     *
     * @param position 位置字符串
     * @return 确实移除了返回 true
     */
    public boolean removeStashPosition(String position) {
        return position != null && stashPositions.remove(position);
    }

    /**
     * 按索引移除存货点。
     *
     * @param index 从 0 开始的下标
     * @return 被移除的位置字符串；下标越界返回 null
     */
    public String removeStashPositionAt(int index) {
        if (index < 0 || index >= stashPositions.size()) return null;
        return stashPositions.remove(index);
    }
}
