package com.billy65536.qab.automatic;

import com.billy65536.qab.integration.QShopBuyCondition;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.block.SignBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * 方块对准工具：把视角转向目标方块、把朝向同步给服务器，并用准星射线确认真的指到了它。
 *
 * <p>购买告示牌（{@link QShopBuyCondition}）与开启存货箱（{@link StashRoutine}）共用这套逻辑。</p>
 *
 * <h3>设计参考</h3>
 * <p>思路取自 <a href="https://github.com/noname-mods/PlayerAPI">PlayerAPI</a> 的两级动作模型：</p>
 * <ul>
 *   <li><b>Forced</b>（{@link #snapLookAt}）—— 一帧到位的精确朝向，用于内部计算；</li>
 *   <li><b>Human</b>（{@link #stepLookAt}）—— 每 tick 限速转动的类人插值，转头过程可见、
 *       也不会出现瞬移式甩头；</li>
 *   <li><b>look-then-do</b> —— 先对准、等朝向稳定并同步到服务端，再执行交互
 *       （由调用方的状态机实现，见 {@link #syncRotation}）。</li>
 * </ul>
 *
 * <h3>三个必须踩对的点</h3>
 * <ol>
 *   <li><b>对准点不能用方块几何中心</b>。墙上告示牌（{@code WallSignBlock}）的实体形状贴在墙面上，
 *       方块中心 {@code (0.5,0.5,0.5)} 落在形状<b>之外</b>；以中心为终点的射线会在够到牌面前就结束，
 *       永远判定"看不到"。必须取
 *       {@linkplain BlockState#getOutlineShape(BlockView, BlockPos, ShapeContext) 轮廓形状}的包围盒中心
 *       —— 见 {@link #aimPoint}。</li>
 *   <li><b>朝向要主动发包</b>。客户端的朝向随移动包在<b>玩家 tick</b> 中发出，而模组逻辑跑在
 *       {@code END_CLIENT_TICK}，晚于发包。若同一 tick 内改完朝向就点击，服务器收到攻击包时
 *       记录的仍是旧朝向。必须显式补发 {@link #syncRotation}。</li>
     *   <li><b>转视角前必须先停 Baritone</b>。Baritone <b>只负责移动身体、不会转头看目标</b>
     *       （见提交 77ce879 的改动说明），因此到达后必须由我们对准并主动发包同步朝向。
     *       仍要先让导航出队再进入对准流程，是因为：玩家必须静止才能精确转头，
     *       且若 Baritone 仍在寻路，它下一步会把玩家带离目标点。</li>
 * </ol>
 */
public final class BlockAimHelper {

    /** 单 tick 允许的最小转动角度，防止配置写 0 导致永远转不到位。 */
    private static final float MIN_STEP_DEG = 1.0F;

    /** 单 tick 允许的最大转动角度（等价于瞬间对准）。 */
    private static final float MAX_STEP_DEG = 180.0F;

    private BlockAimHelper() {
    }

    /** 一组视角朝向。 */
    public record Rotation(float yaw, float pitch) {
    }

    // ==================== 目标点 ====================

    /**
     * 计算目标方块的对准点。
     *
     * <p>取轮廓形状包围盒的中心而非方块几何中心：墙上告示牌、梯子、按钮这类"贴面薄片"方块，
     * 几何中心并不在实体形状内，朝它发射线会打空。形状为空（如空气/纯装饰）时回退到几何中心。</p>
     *
     * @param world 世界（可为 null，此时回退几何中心）
     * @param pos   目标方块
     * @return 世界坐标下的对准点；{@code pos} 为 null 时返回 null
     */
    public static Vec3d aimPoint(BlockView world, BlockPos pos) {
        if (pos == null) return null;
        if (world == null) return Vec3d.ofCenter(pos);

        try {
            BlockState state = world.getBlockState(pos);
            VoxelShape shape = state.getOutlineShape(world, pos, ShapeContext.absent());
            if (shape.isEmpty()) {
                shape = state.getCollisionShape(world, pos);
            }
            if (!shape.isEmpty()) {
                Box box = shape.getBoundingBox();
                return new Vec3d(
                        pos.getX() + (box.minX + box.maxX) * 0.5,
                        pos.getY() + (box.minY + box.maxY) * 0.5,
                        pos.getZ() + (box.minZ + box.maxZ) * 0.5);
            }
        } catch (Exception e) {
            // 形状计算依赖方块状态，异常时不应中断购买流程
        }
        return Vec3d.ofCenter(pos);
    }

    // ==================== 朝向计算 ====================

    /**
     * 计算从 {@code eye} 看向 {@code target} 所需的朝向。
     *
     * @param eye    视点（通常是 {@code player.getEyePos()}）
     * @param target 目标点
     * @return 朝向；任一参数为 null 时返回 null
     */
    public static Rotation rotationTo(Vec3d eye, Vec3d target) {
        if (eye == null || target == null) return null;
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (MathHelper.atan2(-dx, dz) * (180.0 / Math.PI));
        float pitch = (float) (-(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI)));
        return new Rotation(MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0F, 90.0F));
    }

    // ==================== 施加朝向 ====================

    /**
     * 直接写入玩家朝向（含头部/身体朝向），不做插值。
     *
     * <p>不动 {@code prevYaw/prevPitch}：渲染层按 {@code lerp(prev, now, tickDelta)} 插值，
     * 保留上一 tick 的值才能让转头过程平滑可见。</p>
     */
    public static void applyRotation(ClientPlayerEntity player, float yaw, float pitch) {
        if (player == null) return;
        float clamped = MathHelper.clamp(pitch, -90.0F, 90.0F);
        player.setYaw(yaw);
        player.setPitch(clamped);
        player.headYaw = yaw;
        player.bodyYaw = yaw;
    }

    /**
     * 一帧内精确看向目标点（Forced 模式）。
     *
     * @return 施加的朝向；参数非法时返回 null
     */
    public static Rotation snapLookAt(ClientPlayerEntity player, Vec3d target) {
        if (player == null || target == null) return null;
        Rotation want = rotationTo(player.getEyePos(), target);
        if (want == null) return null;
        applyRotation(player, want.yaw(), want.pitch());
        return want;
    }

    /**
     * 朝目标点转动一步（Human 模式，每 tick 调用一次）。
     *
     * <p>每 tick 最多转 {@code maxDegPerTick} 度；剩余偏差小于一步时<b>精确对齐</b>到目标朝向
     * —— 精确对齐是必要的，告示牌很薄，差半度准星就滑出牌面。</p>
     *
     * @param player        玩家
     * @param target        对准点（建议用 {@link #aimPoint} 计算）
     * @param maxDegPerTick 单 tick 最大转动角度
     * @return true 表示本次调用后已<b>精确</b>对准目标
     */
    public static boolean stepLookAt(ClientPlayerEntity player, Vec3d target, float maxDegPerTick) {
        if (player == null || target == null) return false;
        Rotation want = rotationTo(player.getEyePos(), target);
        if (want == null) return false;

        float step = MathHelper.clamp(maxDegPerTick, MIN_STEP_DEG, MAX_STEP_DEG);
        float dYaw = MathHelper.wrapDegrees(want.yaw() - player.getYaw());
        float dPitch = want.pitch() - player.getPitch();

        if (Math.abs(dYaw) <= step && Math.abs(dPitch) <= step) {
            applyRotation(player, want.yaw(), want.pitch());
            return true;
        }
        applyRotation(player,
                player.getYaw() + MathHelper.clamp(dYaw, -step, step),
                player.getPitch() + MathHelper.clamp(dPitch, -step, step));
        return false;
    }

    /**
     * 立即把当前朝向发给服务器。
     *
     * <p>客户端本来只在玩家 tick 里随移动包发朝向，而模组逻辑在 {@code END_CLIENT_TICK} 才改朝向，
     * 不补发就会出现"客户端已经看着牌子、服务器以为你还看着别处"的错位，
     * 交互会被服务端或反作弊判为无效。</p>
     */
    public static void syncRotation(ClientPlayerEntity player) {
        if (player == null || player.networkHandler == null) return;
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                player.getYaw(), player.getPitch(), player.isOnGround()));
    }

    // ==================== 命中校验 ====================

    /**
     * 沿玩家<b>真实视线方向</b>做射线检测，确认准星确实落在目标方块上。
     *
     * <p>这与服务器复算交互时用的射线一致，比"眼睛→方块中心"更能反映实际可点击性。</p>
     *
     * @param reach 最大交互距离
     * @return 准星命中目标方块时返回命中结果（含命中面与命中点）；否则 null
     */
    public static BlockHitResult crosshairHit(MinecraftClient client,
                                              ClientPlayerEntity player,
                                              BlockPos pos,
                                              double reach) {
        if (client == null || client.world == null || player == null || pos == null) {
            return null;
        }
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(reach));
        HitResult result = client.world.raycast(new RaycastContext(
                eye, end,
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
     * 从玩家眼睛向目标方块的{@linkplain #aimPoint 对准点}做射线检测，判断视线是否被遮挡。
     *
     * <p>与 {@link #crosshairHit} 的区别：本方法<b>不依赖</b>当前朝向，用于"转过去之前先看看能不能看到"。</p>
     *
     * @return 命中目标方块时返回命中结果；超距、被遮挡或未命中返回 null
     */
    public static BlockHitResult lineOfSight(MinecraftClient client,
                                             ClientPlayerEntity player,
                                             BlockPos pos,
                                             double reach) {
        if (client == null || client.world == null || player == null || pos == null) {
            return null;
        }
        Vec3d eye = player.getEyePos();
        Vec3d target = aimPoint(client.world, pos);
        if (eye.distanceTo(target) > reach) {
            return null;
        }

        HitResult result = client.world.raycast(new RaycastContext(
                eye, target,
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

    // ==================== 杂项 ====================

    /**
     * 玩家眼睛到方块几何中心的距离，用于"是否走到店门口"的粗判。
     *
     * @return 距离；参数为 null 时返回 {@link Double#MAX_VALUE}
     */
    public static double distanceTo(ClientPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) return Double.MAX_VALUE;
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    /**
     * 描述一次对准的实际状况，用于诊断日志。
     *
     * @param hit {@link #crosshairHit} 的返回值
     * @return 人类可读的诊断串
     */
    public static String describeAim(MinecraftClient client,
                                     ClientPlayerEntity player,
                                     BlockPos pos,
                                     BlockHitResult hit) {
        if (hit != null) {
            return "OK side=" + hit.getSide();
        }
        if (client == null || client.world == null || player == null) {
            return "no-context";
        }
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(64.0));
        HitResult raw = client.world.raycast(new RaycastContext(
                eye, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));

        String crosshair = raw instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
                ? bh.getBlockPos().toShortString()
                : "none(" + raw.getType() + ")";
        boolean los = lineOfSight(client, player, pos, 64.0) != null;
        Rotation want = rotationTo(eye, aimPoint(client.world, pos));
        return String.format("miss crosshair=%s los=%s yaw=%.1f/%.1f pitch=%.1f/%.1f",
                crosshair, los,
                player.getYaw(), want == null ? Float.NaN : want.yaw(),
                player.getPitch(), want == null ? Float.NaN : want.pitch());
    }

    // ==================== 到达判定 ====================

    /**
     * 玩家眼睛到目标方块{@linkplain #aimPoint 对准点}（形状中心）的距离。
     *
     * <p>"是否已走到可交互距离内"的判定应用本方法而非 {@link #distanceTo}：墙上告示牌的形状中心
     * 与几何中心相差约 0.4 格，用几何中心会系统性高估距离。</p>
     *
     * @return 距离；参数为 null 时返回 {@link Double#MAX_VALUE}
     */
    public static double distanceToAimPoint(ClientPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) return Double.MAX_VALUE;
        return player.getEyePos().distanceTo(aimPoint(player.getWorld(), pos));
    }

    /**
     * 玩家是否已走到可交互位置：在 {@code reach} 之内且视线（不依赖朝向）无遮挡。
     *
     * <p>用于"到达判定"，与 {@link #crosshairHit}（依赖准星朝向）互补。
     * 到达判定发生在主动对准之前，此刻准星由玩家自己控制、与牌子无关，用准星会永不满足
     * （即提交 77ce879 修掉的"导航到达后无法点击告示牌、队列卡死"历史 bug，不可回退）。</p>
     *
     * @param reach 最大可交互距离（应已被 clamp 到服务端上限以下）
     * @return 在范围内且视线通畅时为 true
     */
    public static boolean reachedForInteraction(MinecraftClient client,
                                                ClientPlayerEntity player,
                                                BlockPos pos,
                                                double reach) {
        return lineOfSight(client, player, pos, reach) != null;
    }

    // ==================== 站位推算 ====================

    /**
     * 计算玩家应站立（脚下方块坐标）以阅读告示牌的位置：牌子正前方、降至实地、且有头顶空间。
     *
     * <p>墙上告示牌用 {@code HORIZONTAL_FACING} 取正前方；落地告示牌用 {@code ROTATION}（0-15）
     * 经 {@link Direction#fromRotation(double)} 推出朝向。这样 Baritone 的 {@code GoalBlock}
     * 就能落在玩家真正可站的地面上，而不是试图站到牌子方块本身（高处牌子站不上去）。</p>
     *
     * @param world   世界（必须能读到 {@code pos} 处方块状态；维度不符时调用方应传 null 以回退）
     * @param signPos 告示牌坐标
     * @return 可站立的脚下方块坐标；无法推算时返回 null
     */
    public static BlockPos standInFrontOf(World world, BlockPos signPos) {
        if (world == null || signPos == null) return null;
        Direction facing = signFacing(world, signPos);
        if (facing == null) return null;

        // 候选 1：牌子正前方一格，逐步下沉寻找实地
        BlockPos feet = signPos.offset(facing);
        for (int drop = 0; drop < 6; drop++) {
            if (isStandable(world, feet)) return feet;
            feet = feet.down();
        }
        // 候选 2：反方向（朝向约定不确定时兜底）
        feet = signPos.offset(facing.getOpposite());
        for (int drop = 0; drop < 6; drop++) {
            if (isStandable(world, feet)) return feet;
            feet = feet.down();
        }
        return null;
    }

    /** 读取告示牌朝向：墙上牌用 {@code HORIZONTAL_FACING}，落地牌用 {@code ROTATION}。 */
    private static Direction signFacing(World world, BlockPos signPos) {
        BlockState state = world.getBlockState(signPos);
        if (state.contains(WallSignBlock.FACING)) {
            return state.get(WallSignBlock.FACING);
        }
        if (state.contains(SignBlock.ROTATION)) {
            int rot = state.get(SignBlock.ROTATION);
            return Direction.fromRotation((double) rot * 360.0 / 16.0);
        }
        return null;
    }

    /** 脚下方块是否"可站立"：自身是空气、头顶有空气、正下方是实心方块。 */
    private static boolean isStandable(World world, BlockPos feet) {
        return world.getBlockState(feet).isAir()
                && world.getBlockState(feet.up()).isAir()
                && world.getBlockState(feet.down()).isSolidBlock(world, feet.down());
    }
}
