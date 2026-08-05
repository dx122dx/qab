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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 到店判定（{@link NavigationCondition}）+ 到店后的购买时序状态机。
 *
 * <h3>职责切分（重要）</h3>
 * <ul>
 *   <li>{@link #isSatisfied(MinecraftClient)} <b>只判断"走到了没有"</b>。一旦判定到达，
 *       chunkscanner 会把目标出队、队列清空后<b>取消 Baritone</b>；</li>
 *   <li>之后的「对准 → 同步朝向 → 点击 → 发购买命令」由 {@link ShoppingRunner}
 *       每 tick 调用 {@link #tick(MinecraftClient)} 驱动。</li>
 * </ul>
 *
 * <p><b>为什么必须这样切</b>：Baritone 的 LookBehavior 每 tick 都会按路径改写玩家朝向，
 * 且抢在移动包之前生效。寻路没停就转视角，客户端看着像在抽搐、服务器收到的始终是 Baritone 的朝向，
 * 表现为"人到了、头没转向牌子、点不出商店"。所以要先让导航出队把 Baritone 停掉，再从容对准。</p>
 *
 * <h3>购买时序（look-then-do）</h3>
 * <ol>
 *   <li>按 {@link QabConfig#getAimDegPerTick()} 限速转向告示牌
 *       {@linkplain BlockAimHelper#aimPoint 形状中心}（不是方块几何中心，
 *       墙上告示牌的几何中心不在牌面内，朝它发射线必定打空）；</li>
 *   <li>精确对齐后主动补发朝向包，并静置 {@link QabConfig#getAimSettleTicks()} tick
 *       让服务端先记住新朝向；</li>
 *   <li>用<b>准星射线</b>（与服务器复算一致）确认真的指着牌子；</li>
 *   <li>算背包还能装多少 —— QShop 装不下会拒绝发货且客户端无法察觉，必须预判；</li>
 *   <li>左键点击命中面并在下一 tick 松开，随后延时发送购买命令，
 *       全程保持视角锁定在牌子上。</li>
 * </ol>
 */
public final class QShopBuyCondition implements NavigationCondition {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.buy");

    /**
     * 到店判定的额外宽限距离（方块）。
     * Baritone 停下的位置未必精确等于目标点，留出余量避免刚好差一点点就不判定到达。
     */
    private static final double ARRIVE_SLACK = 1.5;

    /** 到店后对准告示牌的最大 tick 数，超时放弃该目标以免队列永久卡死。 */
    private static final int MAX_AIM_TICKS = 100;

    private final BuyTask task;
    private final BlockPos signPos;
    private final QabConfig config;

    /** 是否已走到店门口（导航据此出队并停掉 Baritone）。 */
    private volatile boolean arrived;
    /** 本目标是否已处理完毕（无论买没买成），供 {@link ShoppingRunner} 轮询。 */
    private volatile boolean resolved;
    /** 本次实际购买的数量。 */
    private volatile int boughtAmount;
    /** 本店还没买到的数量。 */
    private volatile int remainingAmount;
    /** 未买完是否因为背包装不下（决定要不要触发存货）。 */
    private volatile boolean blockedByCapacity;
    /** 是否因为始终对不准/点不到而放弃（用于给玩家一条明确提示）。 */
    private volatile boolean aimFailed;

    /** 缓存的对准点，避免每 tick 重算形状。 */
    private Vec3d aimPoint;

    /** 到店后的累计对准 tick 数，用于超时放弃。 */
    private int aimTicks;
    /** 已精确对齐并保持的 tick 数，达到 settle 阈值才允许点击。 */
    private int alignedTicks;

    /** 已发出左键、待下一 tick 松开（模拟真人短按，避免持续破坏方块）。 */
    private boolean releaseAttackPending;

    /** 发送购买命令的倒计时 tick，-1 表示未安排。 */
    private int commandDelayTicks = -1;
    private String pendingCommand;

    public QShopBuyCondition(BuyTask task, QabConfig config) {
        this.task = task;
        this.signPos = task.getSignPos();
        this.config = config;
        this.remainingAmount = task.getAmount();
    }

    // ==================== 状态查询 ====================

    /** 是否已走到店门口（导航已出队、Baritone 已停）。 */
    public boolean isArrived() {
        return arrived;
    }

    /** 本目标是否已处理完毕。 */
    public boolean isResolved() {
        return resolved;
    }

    /** 是否因始终无法对准/点到告示牌而放弃。 */
    public boolean isAimFailed() {
        return aimFailed;
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

    // ==================== 到店判定 ====================

    /**
     * 只判断玩家是否已走到可交互距离内。
     *
     * <p>返回 true 后 chunkscanner 会出队并取消 Baritone，购买动作交由
     * {@link #tick(MinecraftClient)} 完成。这里<b>不</b>做视线检查：
     * 视线被挡是"到店后调整"的问题，若在此处返回 false，Baritone 会一直
     * 试图站到告示牌所在方块上，反而把玩家挤来挤去、永远对不准。</p>
     */
    @Override
    public boolean isSatisfied(MinecraftClient client) {
        if (arrived) return true;
        if (client.player == null || client.world == null) return false;

        double reach = config.getClickReachDist();
        if (BlockAimHelper.distanceTo(client.player, signPos) > reach + ARRIVE_SLACK) {
            return false;
        }

        arrived = true;
        LOGGER.info("Arrived at QShop sign {}, taking over aiming (Baritone released).", signPos);
        return true;
    }

    // ==================== 到店后的购买时序 ====================

    /**
     * 由 {@link ShoppingRunner} 每 tick 调用，推进「对准 → 点击 → 发命令」。
     *
     * <p>只在 {@link #isArrived()} 为 true 后才有效果。</p>
     */
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        ClientPlayerEntity player = client.player;

        // 模拟真人短按：点击的下一 tick 松开左键，避免持续破坏方块
        if (releaseAttackPending) {
            releaseAttackPending = false;
            if (client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
        }

        if (resolved) {
            // 购买命令还没发出去时保持视角锁定，别让玩家把头扭走导致服务端判定异常
            if (hasPendingCommand()) {
                holdAim(client, player);
            }
            tickPendingCommand(client);
            return;
        }
        if (!arrived) return;

        // 1) 转向告示牌形状中心（限速、可见的类人转头）
        Vec3d target = aimTarget(client);
        boolean aligned = BlockAimHelper.stepLookAt(player, target, config.getAimDegPerTick());

        if (aligned) {
            // 2) 主动把朝向发给服务器，并静置若干 tick 让服务端先记住
            BlockAimHelper.syncRotation(player);
            alignedTicks++;
        } else {
            alignedTicks = 0;
        }

        // 3) 准星射线确认真的指着牌子（与服务器复算一致）
        BlockHitResult hit = null;
        if (aligned && alignedTicks > config.getAimSettleTicks()) {
            hit = BlockAimHelper.crosshairHit(client, player, signPos, config.getClickReachDist());
        }

        if (aimTicks % 20 == 0) {
            LOGGER.debug("QAB aim sign={} dist={} aligned={} {}", signPos,
                    String.format("%.2f", BlockAimHelper.distanceTo(player, signPos)),
                    aligned, BlockAimHelper.describeAim(client, player, signPos, hit));
        }

        if (hit == null) {
            if (++aimTicks >= MAX_AIM_TICKS) {
                giveUpAiming(client, player);
            }
            return;
        }

        // 4) 必须真的是告示牌（QShop 商店），否则说明坐标已失效
        BlockEntity be = client.world.getBlockEntity(signPos);
        if (!(be instanceof SignBlockEntity)) {
            if (++aimTicks >= MAX_AIM_TICKS) {
                LOGGER.warn("Block at {} is not a sign anymore, skipping.", signPos);
                giveUpAiming(client, player);
            }
            return;
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
                return;
            }
        }

        if (affordable <= 0) {
            // 一个都装不下：不做无效点击，直接交给 runner 去存货
            LOGGER.info("Inventory full at {}, deferring purchase of {} x{}.",
                    signPos, task.getItemId(), wanted);
            blockedByCapacity = true;
            remainingAmount = wanted;
            resolved = true;
            return;
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
    }

    // ==================== 内部 ====================

    /** 取（必要时计算并缓存）告示牌的对准点。 */
    private Vec3d aimTarget(MinecraftClient client) {
        Vec3d point = aimPoint;
        if (point == null) {
            point = BlockAimHelper.aimPoint(client.world, signPos);
            aimPoint = point;
        }
        return point;
    }

    /** 保持视角锁定在告示牌上（购买命令发出前调用）。 */
    private void holdAim(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d target = aimTarget(client);
        if (BlockAimHelper.stepLookAt(player, target, config.getAimDegPerTick())) {
            BlockAimHelper.syncRotation(player);
        }
    }

    /** 对准超时：放弃该目标，避免整个购买流程卡死。 */
    private void giveUpAiming(MinecraftClient client, ClientPlayerEntity player) {
        LOGGER.warn("Giving up QShop sign at {}: cannot aim at it after {} ticks. {}",
                signPos, MAX_AIM_TICKS,
                BlockAimHelper.describeAim(client, player, signPos, null));
        // 非容量原因放弃：不触发存货，剩余量记为 0 让 runner 跳过
        remainingAmount = 0;
        blockedByCapacity = false;
        aimFailed = true;
        resolved = true;
    }

    /**
     * 执行自动购买：左键点击告示牌，随后延时发送购买命令。
     *
     * @param side   准星命中的方块面。必须用真实命中面，
     *               硬编码 {@link Direction#UP} 对墙上告示牌是错误的面，服务器可能拒绝该交互。
     * @param amount 本次实际购买数量
     */
    private void performPurchase(MinecraftClient client, Direction side, int amount) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        try {
            if (client.interactionManager != null) {
                // 再补一次朝向包：确保服务器处理攻击包时用的就是"看着牌子"的朝向
                BlockAimHelper.syncRotation(player);
                client.interactionManager.attackBlock(signPos, side);
                player.swingHand(Hand.MAIN_HAND);
                releaseAttackPending = true;
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
