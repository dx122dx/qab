package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.core.navigation.NavigationCondition;
import com.billy65536.qab.automatic.BlockAimHelper;
import com.billy65536.qab.automatic.BuyTask;
import com.billy65536.qab.automatic.InventoryCapacityCalculator;
import com.billy65536.qab.automatic.ShoppingRunner;
import com.billy65536.qab.config.QabConfig;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导航到达条件 + 自动购买副作用（带背包容量预判与部分购买）。
 *
 * <p>作为 {@link NavigationCondition} 注入 {@code ChunkScannerNavigation}：</p>
 * <ul>
 *   <li>{@link #isSatisfied(MinecraftClient)} 先判断玩家是否走到了告示牌附近；</li>
 *   <li>到位后<b>主动把视角转向告示牌</b>（Baritone 只负责移动身体，不会转头看目标），
 *       再用射线检测确认视线未被遮挡；</li>
 *   <li>下单前<b>计算背包还能装多少</b>，装不下就少买或不买；</li>
 *   <li>处理完毕后 {@link #isResolved()} 转 true，由 {@link ShoppingRunner} 决定后续。</li>
 * </ul>
 *
 * <h3>为什么要容量预判</h3>
 * <p>QShop 在玩家背包空间不足时会<b>拒绝发货</b>，且这是纯服务端行为，
 * 客户端无法从响应可靠检测失败。若不预判，表现为「计划显示成功、东西没到手」。
 * 因此必须在下单前按 {@code Item.getMaxCount()} 算准能装几个，
 * 装不下的部分交给 {@link ShoppingRunner} 存货后再回来买。</p>
 *
 * <h3>购买流程</h3>
 * <ol>
 *   <li>转动视角对准告示牌，射线确认命中；</li>
 *   <li>计算可购买量：装不下一个就直接标记容量受阻，不做无效点击；</li>
 *   <li>左键点击告示牌<b>命中面</b>（等价于玩家手动左键，服务器识别为商店交互）；</li>
 *   <li>等待 {@code config.buyDelayMs} 毫秒后发送 {@code config.buyCommand}
 *       （{@code {count}} 替换为<b>本次实际购买量</b>）。</li>
 * </ol>
 */
public final class QShopBuyCondition implements NavigationCondition {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.buy");

    /**
     * 到位判定的额外宽限距离（方块）。
     * Baritone 停下的位置未必精确等于目标点，留出余量避免刚好差一点点就不判定到达。
     */
    private static final double ARRIVE_SLACK = 1.5;

    /** 视线被遮挡时的最大重试 tick 数，超时后放弃该目标以免队列永久卡死。 */
    private static final int MAX_AIM_TICKS = 60;

    private final BuyTask task;
    private final BlockPos signPos;
    private final QabConfig config;

    /** 本目标是否已处理完毕（无论买没买成），供 {@link ShoppingRunner} 轮询。 */
    private volatile boolean resolved;
    /** 本次实际购买的数量。 */
    private volatile int boughtAmount;
    /** 本店还没买到的数量。 */
    private volatile int remainingAmount;
    /** 未买完是否因为背包装不下（决定要不要触发存货）。 */
    private volatile boolean blockedByCapacity;

    /** 已进入「到位并尝试对准」阶段的累计 tick 数，用于超时放弃。 */
    private int aimTicks;

    /** 发送购买命令的倒计时 tick，-1 表示未安排。 */
    private int commandDelayTicks = -1;
    private String pendingCommand;

    public QShopBuyCondition(BuyTask task, QabConfig config) {
        this.task = task;
        this.signPos = task.getSignPos();
        this.config = config;
        this.remainingAmount = task.getAmount();
    }

    /** 本目标是否已处理完毕。 */
    public boolean isResolved() {
        return resolved;
    }

    /**
     * 是否还有待发送的购买命令。
     *
     * <p>{@link ShoppingRunner} 必须等它变为 false 才能投递下一个目标，
     * 否则玩家会在命令发出前就被 Baritone 带离商店。</p>
     */
    public boolean hasPendingCommand() {
        return commandDelayTicks >= 0 && pendingCommand != null;
    }

    /**
     * 由 {@link ShoppingRunner} 每 tick 调用，推进延时购买命令。
     *
     * <p>目标被判定到达后导航队列即弹出该目标，{@link #isSatisfied} 不再被调用，
     * 因此延时命令必须由外部继续驱动。</p>
     */
    public void tickCommand(MinecraftClient client) {
        tickPendingCommand(client);
    }

    /** 本次实际购买的数量。 */
    public int getBoughtAmount() {
        return boughtAmount;
    }

    /** 本店还没买到的数量。 */
    public int getRemainingAmount() {
        return remainingAmount;
    }

    /** 未买完是否因为背包容量不足。 */
    public boolean isBlockedByCapacity() {
        return blockedByCapacity;
    }

    @Override
    public boolean isSatisfied(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;

        // 已处理过则视为到达（让队列继续推进，不再重复购买）
        if (resolved) {
            tickPendingCommand(client);
            return true;
        }

        ClientPlayerEntity player = client.player;

        // 1) 必须是告示牌（QShop 商店）。方块未加载时 be 为 null，继续等待区块加载。
        BlockEntity be = client.world.getBlockEntity(signPos);
        if (!(be instanceof SignBlockEntity)) {
            return false;
        }

        // 2) 先判断是否走到「可交互距离」内：只有离牌子够近才接管视角，
        //    否则会在赶路途中不断把玩家的头拧向目标，干扰 Baritone 寻路。
        //    不假设玩家的站位（牌子可能在墙上/柱上/半空），只看距离是否够近能点到。
        double reach = config.getClickReachDist();
        if (BlockAimHelper.distanceTo(player, signPos) > reach + ARRIVE_SLACK) {
            aimTicks = 0;
            return false;
        }

        // 3) 到位了：主动把视角转向牌子中心。
        //    Baritone 只负责把身体走过去，不会转头看目标，必须由我们自己对准。
        BlockAimHelper.lookAt(player, Vec3d.ofCenter(signPos));

        // 4) 射线检测确认视线能命中牌子本身（任意面均可：侧/上/下，不挑剔）。
        BlockHitResult hit = BlockAimHelper.raycastTo(client, player, signPos, reach);

        if (aimTicks % 10 == 0) {
            LOGGER.debug("QAB aim sign={} dist={} {}", signPos,
                    String.format("%.2f", BlockAimHelper.distanceTo(player, signPos)),
                    BlockAimHelper.describeHit(client, player, signPos, hit));
        }

        if (hit == null) {
            // 视线被挡或超距：给若干 tick 重试机会，超时则放弃该目标避免队列卡死
            if (++aimTicks >= MAX_AIM_TICKS) {
                LOGGER.warn("Giving up QShop sign at {}: no line of sight after {} ticks.",
                        signPos, MAX_AIM_TICKS);
                // 非容量原因放弃：不触发存货，剩余量记为 0 让 runner 跳过
                remainingAmount = 0;
                blockedByCapacity = false;
                resolved = true;
                return true;
            }
            return false;
        }

        // 5) 下单前算容量：QShop 背包不足会拒绝发货，必须先判断能装多少
        int wanted = task.getAmount();
        int affordable = ShoppingRunner.affordableAmount(client, task.getItemId(), wanted, config);

        if (affordable < 0) {
            // 物品 ID 解析失败：无法预判容量。退化为「按空格子有无」保守判断，
            // 有空位就整单买，没空位就去存货，避免因数据问题彻底卡死。
            boolean hasRoom = InventoryCapacityCalculator.emptySlots(player) > 0;
            LOGGER.warn("Cannot resolve item '{}' for capacity check; falling back to {}.",
                    task.getItemId(), hasRoom ? "full purchase" : "stash");
            if (hasRoom) {
                affordable = wanted;
            } else {
                blockedByCapacity = true;
                remainingAmount = wanted;
                resolved = true;
                return true;
            }
        }

        if (affordable <= 0) {
            // 一个都装不下：不做无效点击，直接交给 runner 去存货
            LOGGER.info("Inventory full at {}, deferring purchase of {} x{}.",
                    signPos, task.getItemId(), wanted);
            blockedByCapacity = true;
            remainingAmount = wanted;
            resolved = true;
            return true;
        }

        // 6) 执行购买（可能是部分购买）
        boughtAmount = affordable;
        remainingAmount = wanted - affordable;
        blockedByCapacity = remainingAmount > 0;
        resolved = true;

        if (remainingAmount > 0) {
            LOGGER.info("Partial purchase at {}: buying {} of {} x{} (rest after stashing).",
                    signPos, affordable, task.getItemId(), wanted);
        }
        performPurchase(client, hit.getSide(), affordable);
        return true;
    }

    /**
     * 执行自动购买：左键点击告示牌，随后延时发送购买命令。
     *
     * @param side   射线命中的方块面。必须用真实命中面，
     *               硬编码 {@link Direction#UP} 对墙上告示牌是错误的面，服务器可能拒绝该交互。
     * @param amount 本次实际购买数量
     */
    private void performPurchase(MinecraftClient client, Direction side, int amount) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        try {
            if (client.interactionManager != null) {
                client.interactionManager.attackBlock(signPos, side);
                // 同步挥手动作，部分服务端依赖挥手包判定交互有效
                player.swingHand(player.getActiveHand());
            }
            LOGGER.info("QAB auto-clicked QShop sign at {} (side={}) for {} item(s)",
                    signPos, side, amount);
        } catch (Exception e) {
            LOGGER.warn("Failed to auto-click QShop sign at {}: {}", signPos, e.getMessage());
        }

        // 延时发送购买命令。用 tick 计时而非 java.util.Timer：
        // 后者要额外切回主线程，且模组卸载/断线时线程可能残留。
        pendingCommand = config.getBuyCommand().replace("{count}", String.valueOf(amount));
        commandDelayTicks = Math.max(0, config.getBuyDelayMs()) / 50; // 20 tick/s
    }

    /** 推进购买命令的延时发送。 */
    private void tickPendingCommand(MinecraftClient client) {
        if (commandDelayTicks < 0 || pendingCommand == null) return;
        if (commandDelayTicks > 0) {
            commandDelayTicks--;
            return;
        }

        String command = pendingCommand;
        pendingCommand = null;
        commandDelayTicks = -1;

        ClientPlayerEntity player = client.player;
        if (player == null) return;
        try {
            if (command.startsWith("/")) {
                player.networkHandler.sendChatCommand(command.substring(1));
            } else {
                player.networkHandler.sendChatMessage(command);
            }
            LOGGER.info("QAB sent buy command: {}", command);
        } catch (Exception e) {
            LOGGER.warn("Failed to send buy command '{}': {}", command, e.getMessage());
        }
    }
}
