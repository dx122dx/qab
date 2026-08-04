package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.planner.model.PlanEntry;
import com.billy65536.qab.planner.model.ShoppingPlan;

import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 将 QAB 购物计划接入 chunkscanner 导航门面，实现"按规划自动寻路 + 到达自动购买"。
 *
 * <p>调用 {@link #applyPlan(ShoppingPlan, QabConfig)} 时：
 * <ol>
 *   <li>解析每条 {@link PlanEntry#getPosition()}（格式 {@code dimension(x,y,z)}）；</li>
 *   <li>构造 {@link NavigationEntry} + {@link QShopBuyCondition}（携带购买总量与配置）；</li>
 *   <li>逐条 {@link ChunkScannerNavigation#enqueue(NavigationEntry, com.billy65536.chunkscanner.core.navigation.NavigationCondition) 入队}；</li>
 *   <li>最后 {@link ChunkScannerNavigation#start() 启动}导航。</li>
 * </ol>
 *
 * 维度切换由 chunkscanner 导航门面自动暂停/恢复。
 */
public final class CsNavigationHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.nav");

    private CsNavigationHelper() {
    }

    /**
     * 将计划中的全部目标入队并启动导航。
     *
     * @param plan   购物计划（含 position + 购买量）
     * @param config QAB 配置（延时、命令模板、可点击距离）
     * @return 成功入队的目标数量（解析失败的目标会被跳过并记录）
     */
    public static int applyPlan(ShoppingPlan plan, QabConfig config) {
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            return 0;
        }

        ChunkScannerNavigation nav = ChunkScannerNavigation.get();
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
