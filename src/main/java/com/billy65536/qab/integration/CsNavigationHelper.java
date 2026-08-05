package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.api.NavigationApi;
import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.planner.model.PlanEntry;
import com.billy65536.qab.planner.model.ShoppingPlan;

import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 将 QAB 购物计划接入 chunkscanner 导航，实现"按规划自动寻路 + 到达自动购买"。
 *
 * <p>使用 chunkscanner 公共 API 的<b>独立导航实例</b>（{@code NavigationApi.createNavigation}），
 * 而非全局共享队列，因此 QAB 的购物路线与玩家自己的 {@code /cs nav} 队列互不干扰，
 * 也不会被对方清空。实例已注册 tick 托管，由 chunkscanner 每 tick 自动推进。</p>
 *
 * <p>调用 {@link #applyPlan(ShoppingPlan, QabConfig)} 时：
 * <ol>
 *   <li>清空上一轮遗留的队列（同一时刻只跑一份购物计划）；</li>
 *   <li>解析每条 {@link PlanEntry#getPosition()}（格式 {@code dimension(x,y,z)}）；</li>
 *   <li>构造 {@link NavigationEntry} + {@link QShopBuyCondition}（携带购买总量与配置）并入队；</li>
 *   <li>最后启动导航。</li>
 * </ol>
 *
 * <p>维度切换由 chunkscanner 导航自动暂停/恢复。</p>
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
     * 将计划中的全部目标入队并启动导航。
     *
     * <p>会先清空本实例上一轮遗留的队列，确保同一时刻只执行一份购物计划。</p>
     *
     * @param plan   购物计划（含 position + 购买量）
     * @param config QAB 配置（延时、命令模板、可点击距离）
     * @return 成功入队的目标数量（解析失败的目标会被跳过并记录）
     */
    public static int applyPlan(ShoppingPlan plan, QabConfig config) {
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            return 0;
        }

        ChunkScannerNavigation nav = navigation();
        // 清空上一轮残留目标，避免新旧计划混在同一队列里执行
        nav.clear();

        int queued = 0;
        for (PlanEntry entry : plan.getPlan()) {
            ParsedPos pp = parsePosition(entry.getPosition());
            if (pp == null) {
                LOGGER.warn("Skipping plan entry with unparseable position: {}", entry.getPosition());
                continue;
            }
            int buyCount = entry.getTotal(); // count + redundancy

            BlockPos signPos = new BlockPos(pp.x, pp.y, pp.z);
            // 导航目标即告示牌方块格本身：Baritone 会停在它相邻的某个可站立格
            // （地面牌→牌下方地面，墙牌→牌前方地面，悬空牌→相邻可站格）。
            // 不假设"牌前固定一格"，因为牌子可能在墙上/柱上/半空，没有通用的前方落点。
            // 是否能点到由 QShopBuyCondition 按"距离 + 视线命中"判定。
            NavigationEntry navEntry = new NavigationEntry(
                    pp.dimensionId, pp.x, pp.y, pp.z);
            QShopBuyCondition cond = new QShopBuyCondition(signPos, buyCount, config);
            nav.enqueue(navEntry, cond);
            queued++;
        }

        if (queued > 0) {
            nav.start();
            LOGGER.info("QAB navigation applied: {} target(s) queued.", queued);
        }
        return queued;
    }

    /**
     * 中止 QAB 导航并清空其队列。
     *
     * <p>不影响玩家自己的 {@code /cs nav} 全局队列。</p>
     *
     * @return 被清空的剩余目标数
     */
    public static int stop() {
        ChunkScannerNavigation nav = navigation;
        if (nav == null) return 0;
        int remaining = nav.size();
        nav.stop();
        LOGGER.info("QAB navigation stopped, {} target(s) discarded.", remaining);
        return remaining;
    }

    /** QAB 导航队列中剩余的目标数。 */
    public static int size() {
        ChunkScannerNavigation nav = navigation;
        return nav == null ? 0 : nav.size();
    }

    /** QAB 导航是否正在进行。 */
    public static boolean isActive() {
        ChunkScannerNavigation nav = navigation;
        return nav != null && nav.isActive();
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
    static ParsedPos parsePosition(String position) {
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

    /** 解析后的位置。 */
    static final class ParsedPos {
        final String dimensionId;
        final int x, y, z;

        ParsedPos(String dimensionId, int x, int y, int z) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
