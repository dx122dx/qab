package com.billy65536.qab.automatic;

import com.billy65536.qab.integration.QShopBuyCondition;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * 方块对准工具：把视角转向目标方块并用射线确认视线未被遮挡。
 *
 * <p>购买告示牌（{@link QShopBuyCondition}）与开启存货箱（{@link StashRoutine}）
 * 都需要这套逻辑，抽出来共用。</p>
 *
 * <h3>为什么必须自己转视角</h3>
 * <p>Baritone 只负责把身体走过去，<b>不会转头看目标</b>。玩家走到点位后视角朝向是随机的，
 * 直接读 {@code client.crosshairTarget} 判断是否指向目标方块几乎永远不成立 ——
 * 表现为「人到了但点不到」，队列卡死不再推进。必须由调用方接管视角主动 lookAt。</p>
 */
public final class BlockAimHelper {

    private BlockAimHelper() {
    }

    /**
     * 把玩家视角对准目标点（单帧直接设置）。
     *
     * <p>直接改 yaw/pitch 即可，客户端会在下一个 tick 通过移动包同步给服务器。
     * 同时同步 headYaw/bodyYaw，避免服务器侧朝向判定与客户端不一致。</p>
     *
     * @param player 玩家
     * @param target 目标点（世界坐标）
     */
    public static void lookAt(ClientPlayerEntity player, Vec3d target) {
        if (player == null || target == null) return;

        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (MathHelper.atan2(-dx, dz) * (180.0 / Math.PI));
        float pitch = (float) (-(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI)));

        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        player.headYaw = yaw;
        player.bodyYaw = yaw;
    }

    /**
     * 从玩家眼睛向目标方块中心做射线检测，确认视线能命中该方块（任意面均可）。
     *
     * @param client 客户端
     * @param player 玩家
     * @param pos    目标方块坐标
     * @param reach  最大可交互距离
     * @return 命中目标方块时返回命中结果（含命中的那一面）；超距、被遮挡或未命中返回 null
     */
    public static BlockHitResult raycastTo(MinecraftClient client,
                                           ClientPlayerEntity player,
                                           BlockPos pos,
                                           double reach) {
        if (client == null || client.world == null || player == null || pos == null) {
            return null;
        }

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        if (eye.distanceTo(center) > reach) {
            return null;
        }

        HitResult result = client.world.raycast(new RaycastContext(
                eye, center,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player));

        if (result instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(pos)) {
            return blockHit;
        }
        return null;
    }

    /**
     * 玩家眼睛到方块中心的距离。
     *
     * @param player 玩家
     * @param pos    方块坐标
     * @return 距离；玩家为 null 时返回 {@link Double#MAX_VALUE}
     */
    public static double distanceTo(ClientPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) return Double.MAX_VALUE;
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    /**
     * 描述一次射线检测的结果，用于诊断日志。
     *
     * @param client 客户端
     * @param player 玩家
     * @param pos    期望命中的方块
     * @param hit    {@link #raycastTo} 的返回值
     * @return 人类可读的诊断串
     */
    public static String describeHit(MinecraftClient client,
                                     ClientPlayerEntity player,
                                     BlockPos pos,
                                     BlockHitResult hit) {
        if (hit != null) {
            return "OK side=" + hit.getSide();
        }
        if (client == null || client.world == null || player == null) {
            return "no-context";
        }
        HitResult raw = client.world.raycast(new RaycastContext(
                player.getEyePos(), Vec3d.ofCenter(pos),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        if (raw instanceof BlockHitResult bh) {
            return "miss hit=" + bh.getBlockPos() + " side=" + bh.getSide();
        }
        return "no-block(" + raw.getType() + ")";
    }
}
