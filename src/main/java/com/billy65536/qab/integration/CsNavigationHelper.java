package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.api.NavigationApi;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.qab.automatic.ShoppingRunner;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.planner.model.ShoppingPlan;

import net.minecraft.client.MinecraftClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QAB 购买导航实例的持有者与对外门面。
 *
 * <p>使用 chunkscanner 公共 API 的<b>独立导航实例</b>（{@code NavigationApi.createNavigation}），
 * 而非全局共享队列，因此 QAB 的购物路线与玩家自己的 {@code /cs nav} 队列互不干扰，
 * 也不会被对方清空。实例已注册 tick 托管，由 chunkscanner 每 tick 自动推进。</p>
 *
 * <h3>与 {@link ShoppingRunner} 的分工</h3>
 * <p>本类只负责<b>持有导航实例</b>与提供查询/中止入口；具体的「按计划逐店购买」
 * 由 {@link ShoppingRunner} 编排。原因是要支持「买不完就把剩余量回插队列」，
 * 必须由 QAB 自己持有待办队列、一次只向导航投递一个目标 ——
 * chunkscanner 的 {@code clear()} 等同 {@code stop()}，没有插队/移除单目标的 API。</p>
 *
 * <p><b>维度</b>：坐标只在其所属维度内有意义，因此投递目标前必须做维度匹配
 * （{@link #inDimension(MinecraftClient, String)}）。chunkscanner 导航只会在
 * 「启动后玩家换了维度」时暂停，并不校验目标本身属于哪个维度，跨维度目标会让
 * Baritone 朝当前维度的同名坐标寻路。</p>
 *
 * <p><b>注意</b>：Baritone 路径执行是全局唯一资源。若玩家同时启动了 {@code /cs nav}，
 * 两边会互相抢占寻路目标。{@link #stop()} 可随时中止 QAB 侧导航。</p>
 */
public final class CsNavigationHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.nav");

    /** 独立导航实例名称，用于日志与回退路径点分组隔离。 */
    private static final String NAV_NAME = "qab";

    /** QAB 专属导航实例（懒初始化，首次使用时创建并注册 tick 托管）。 */
    private static volatile ChunkScannerNavigation navigation;

    private CsNavigationHelper() {
    }

    /**
     * 获取（必要时创建）QAB 专属的独立导航实例。
     *
     * <p>首次调用时创建实例并注册到 chunkscanner 的 tick 托管器，
     * 之后由 chunkscanner 每 tick 自动推进队列，无需 QAB 自行挂钩事件。</p>
     */
    public static ChunkScannerNavigation navigation() {
        ChunkScannerNavigation nav = navigation;
        if (nav == null) {
            synchronized (CsNavigationHelper.class) {
                nav = navigation;
                if (nav == null) {
                    nav = NavigationApi.createNavigation(NAV_NAME);
                    NavigationApi.manageTick(nav);
                    navigation = nav;
                    LOGGER.info("QAB navigation instance created and managed by chunkscanner.");
                }
            }
        }
        return nav;
    }

    /**
     * 获取已创建的导航实例，<b>不触发创建</b>。
     *
     * <p>供 {@link ShoppingRunner} 在停止/查询路径上使用，避免只是想中止却先建出一个实例。</p>
     *
     * @return 尚未创建时返回 null
     */
    public static ChunkScannerNavigation navigationIfPresent() {
        return navigation;
    }

    /**
     * 按计划开始自动购买（寻路 + 到店购买 + 容量不足时自动存货）。
     *
     * @param plan   购物计划（须为可购买格式，调用方先校验 {@link ShoppingPlan#isBuyable()}）
     * @param config QAB 配置
     * @return 成功入队的目标数量（坐标解析失败的目标会被跳过并记录）
     */
    public static int applyPlan(ShoppingPlan plan, QabConfig config) {
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            return 0;
        }
        return ShoppingRunner.getInstance().start(plan, config);
    }

    /**
     * 中止 QAB 购买流程（含存货子流程）并清空队列。
     *
     * <p>不影响玩家自己的 {@code /cs nav} 全局队列。</p>
     *
     * @return 被清空的剩余目标数
     */
    public static int stop() {
        int remaining = ShoppingRunner.getInstance().remaining();
        ShoppingRunner.getInstance().stop();
        LOGGER.info("QAB shopping stopped, {} target(s) discarded.", remaining);
        return remaining;
    }

    /** 剩余待购买的目标数。 */
    public static int size() {
        return ShoppingRunner.getInstance().remaining();
    }

    /** QAB 购买流程是否正在进行。 */
    public static boolean isActive() {
        return ShoppingRunner.getInstance().isRunning();
    }

    /**
     * Baritone 是否可用。
     *
     * <p>为 {@code false} 时 chunkscanner 会降级为创建 Xaero 路径点，
     * 玩家需自行前往，自动购买仍会在到达后触发。</p>
     */
    public static boolean isBaritoneAvailable() {
        return NavigationApi.isBaritoneAvailable();
    }

    /**
     * 解析位置字符串 {@code dimension(x,y,z)} 为结构化坐标。
     *
     * <p>示例：{@code "minecraft:overworld(12,65,-13)"} →
     * dimensionId="minecraft:overworld", x=12, y=65, z=-13。</p>
     *
     * @param position 位置字符串
     * @return 解析结果，格式非法时返回 null
     */
    public static ParsedPos parsePosition(String position) {
        if (position == null || position.isEmpty()) return null;
        int open = position.indexOf('(');
        int close = position.lastIndexOf(')');
        if (open <= 0 || close <= open + 1) return null;

        String dimensionId = position.substring(0, open).trim();
        if (dimensionId.isEmpty()) return null;

        String coordPart = position.substring(open + 1, close);
        String[] parts = coordPart.split(",");
        if (parts.length != 3) return null;

        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new ParsedPos(dimensionId, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 玩家当前所在维度 id，如 {@code minecraft:overworld}。
     *
     * <p>取 {@code world.getRegistryKey()}（世界 id）而非 {@code getDimensionKey()}（维度类型 id）：
     * 前者才是 chunkscanner 数据库与 {@code /qab stash add} 写入位置字符串时用的那一个，
     * 二者对自定义维度并不相等。</p>
     *
     * @return 维度 id；尚未进入世界时返回 null
     */
    public static String currentDimension(MinecraftClient client) {
        if (client == null || client.world == null) return null;
        return client.world.getRegistryKey().getValue().toString();
    }

    /**
     * 归一化维度 id：去空白，缺省命名空间时补 {@code minecraft:}。
     *
     * @return 归一化结果；输入为 null 或空白时返回 null
     */
    public static String normalizeDimension(String dimensionId) {
        if (dimensionId == null) return null;
        String s = dimensionId.trim();
        if (s.isEmpty()) return null;
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    /**
     * 目标维度是否就是玩家当前所在维度。
     *
     * <p>缺失维度标注（null/空）的旧数据视为匹配，避免因数据不全直接卡死流程。</p>
     *
     * @param dimensionId 目标维度 id，可省略命名空间
     * @return 匹配返回 true；玩家不在世界中返回 false
     */
    public static boolean inDimension(MinecraftClient client, String dimensionId) {
        String target = normalizeDimension(dimensionId);
        if (target == null) return true;
        String current = currentDimension(client);
        return current != null && current.equals(target);
    }

    /**
     * 把维度与坐标格式化为位置字符串 {@code dimension(x,y,z)}。
     *
     * <p>与 {@link #parsePosition(String)} 互逆，供 {@code /qab stash add} 记录当前位置。</p>
     */
    public static String formatPosition(String dimensionId, int x, int y, int z) {
        return dimensionId + "(" + x + "," + y + "," + z + ")";
    }

    /** 解析后的位置。 */
    public static final class ParsedPos {
        public final String dimensionId;
        public final int x, y, z;

        ParsedPos(String dimensionId, int x, int y, int z) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
