package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.core.navigation.NavigationCondition;
import com.billy65536.qab.config.QabConfig;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 导航到达条件 + 自动购买副作用。
 *
 * <p>作为 {@link NavigationCondition} 注入 {@code ChunkScannerNavigation}：
 * <ul>
 *   <li>{@link #isSatisfied(MinecraftClient)} 先判断玩家是否走到了告示牌附近；</li>
 *   <li>到位后<b>主动把视角转向告示牌</b>（Baritone 只负责移动身体，不会转头看目标），
 *       再用射线检测确认视线未被遮挡；</li>
 *   <li>确认可点击后执行购买副作用，并返回 {@code true} 让导航队列弹出该目标；</li>
 *   <li>幂等保护：同一目标只购买一次，避免每帧重复触发。</li>
 * </ul>
 *
 * <h3>为什么必须自己转视角</h3>
 * <p>旧实现直接读 {@code client.crosshairTarget} 判断是否指向告示牌。但导航过程中
 * 没有任何环节会把准星对准目标方块，玩家走到点位后视角朝向是随机的，
 * 该条件几乎永远不成立 —— 表现为「人到了但点不到牌子」，队列卡死不再推进。
 * 现在改为到位后由本类接管视角，主动 {@code lookAt} 告示牌。
 *
 * <h3>购买流程</h3>
 * <ol>
 *   <li>转动视角对准告示牌，射线确认命中；</li>
 *   <li>左键点击告示牌<b>命中面</b>（等价于玩家手动左键，服务器识别为商店交互）；</li>
 *   <li>等待 {@code config.buyDelayMs} 毫秒（默认 500）；</li>
 *   <li>发送 {@code config.buyCommand}（{@code {count}} 替换为购买总量）。</li>
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

    private final BlockPos signPos;
    private final int buyCount;
    private final QabConfig config;
    private final AtomicBoolean triggered = new AtomicBoolean(false);

    /** 已进入「到位并尝试对准」阶段的累计 tick 数，用于超时放弃。 */
    private int aimTicks;

    /**
     * @param signPos  目标告示牌坐标
     * @param buyCount 购买总量（count + redundancy），替换命令模板中的 {@code {count}}
     * @param config   QAB 配置（延时、命令模板、可点击距离）
     */
    public QShopBuyCondition(BlockPos signPos, int buyCount, QabConfig config) {
        this.signPos = signPos;
        this.buyCount = buyCount;
        this.config = config;
    }

    @Override
    public boolean isSatisfied(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;

        // 已触发过则视为到达（让队列继续推进，不再重复购买）
        if (triggered.get()) return true;

        ClientPlayerEntity player = client.player;

        // 1) 必须是告示牌（QShop 商店）。方块未加载时 be 为 null，继续等待区块加载。
        BlockEntity be = client.world.getBlockEntity(signPos);
        if (!(be instanceof SignBlockEntity)) {
            return false;
        }

        // 2) 先判断是否走到附近：只有进入可点击范围才接管视角，
        //    否则会在赶路途中不断把玩家的头拧向目标，干扰 Baritone 寻路。
        Vec3d eye = player.getEyePos();
        Vec3d signCenter = Vec3d.ofCenter(signPos);
        double reach = config.getClickReachDist();
        if (eye.distanceTo(signCenter) > reach + ARRIVE_SLACK) {
            aimTicks = 0;
            return false;
        }

        // 3) 到位了：主动把视角转向告示牌中心。
        //    Baritone 只负责把身体走过去，不会转头看目标，必须由我们自己对准。
        lookAt(player, signCenter);

        // 4) 射线检测确认真的能点到（视线未被墙体/其他方块遮挡，且在 reach 距离内）
        BlockHitResult hit = raycastToSign(client, player, reach);
        if (hit == null) {
            // 视线被挡或超距：给若干 tick 重试机会，超时则放弃该目标避免队列卡死
            if (++aimTicks >= MAX_AIM_TICKS) {
                LOGGER.warn("Giving up QShop sign at {}: no line of sight after {} ticks.",
                        signPos, MAX_AIM_TICKS);
                return true; // 返回 true 让队列弹出，继续下一个目标
            }
            return false;
        }

        // 5) 满足条件：执行购买副作用（一次性）
        if (triggered.compareAndSet(false, true)) {
            performPurchase(client, hit.getSide());
        }
        return true;
    }

    /**
     * 把玩家视角平滑地（单帧直接设置）对准目标点。
     *
     * <p>直接改 yaw/pitch 即可，客户端会在下一个 tick 通过移动包同步给服务器。
     */
    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI)));

        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        // 同步 headYaw/bodyYaw，避免服务器侧朝向判定与客户端不一致
        player.headYaw = yaw;
        player.bodyYaw = yaw;
    }

    /**
     * 从玩家眼睛向告示牌中心做射线检测，确认命中的正是目标告示牌。
     *
     * @return 命中目标告示牌时返回命中结果（含命中面）；被遮挡或未命中返回 null
     */
    private BlockHitResult raycastToSign(MinecraftClient client,
                                         ClientPlayerEntity player,
                                         double reach) {
        Vec3d eye = player.getEyePos();
        Vec3d signCenter = Vec3d.ofCenter(signPos);
        if (eye.distanceTo(signCenter) > reach) {
            return null;
        }

        HitResult result = client.world.raycast(new RaycastContext(
                eye, signCenter,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player));

        if (result instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(signPos)) {
            return blockHit;
        }
        return null;
    }

    /**
     * 执行自动购买：左键点击告示牌，延时发送购买命令。
     *
     * @param side 射线命中的方块面。必须用真实命中面，
     *             硬编码 {@link Direction#UP} 对墙上告示牌是错误的面，服务器可能拒绝该交互。
     */
    private void performPurchase(MinecraftClient client, Direction side) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        // 1. 左键点击告示牌（等价于玩家手动左键，会向服务器发送攻击包）
        try {
            if (client.interactionManager != null) {
                client.interactionManager.attackBlock(signPos, side);
                // 同步挥手动作，部分服务端依赖挥手包判定交互有效
                player.swingHand(player.getActiveHand());
            }
            LOGGER.info("QAB auto-clicked QShop sign at {} (side={}) for {} item(s)",
                    signPos, side, buyCount);
        } catch (Exception e) {
            LOGGER.warn("Failed to auto-click QShop sign at {}: {}", signPos, e.getMessage());
        }

        // 2. 延时发送购买命令（替换 {count}）
        String command = config.getBuyCommand().replace("{count}", String.valueOf(buyCount));
        int delay = Math.max(0, config.getBuyDelayMs());
        new Timer("qab-buy-" + signPos, true).schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    client.execute(() -> {
                        ClientPlayerEntity p = client.player;
                        if (p != null) {
                            if (command.startsWith("/")) {
                                p.networkHandler.sendChatCommand(command.substring(1));
                                LOGGER.info("QAB sent buy command: {}", command);
                            } else {
                                p.networkHandler.sendChatMessage(command);
                                LOGGER.info("QAB sent buy message: {}", command);
                            }
                        }
                    });
                } catch (Exception e) {
                    LOGGER.warn("Failed to send buy command '{}': {}", command, e.getMessage());
                }
            }
        }, delay);
    }
}
