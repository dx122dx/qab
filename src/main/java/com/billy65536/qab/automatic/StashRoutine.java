package com.billy65536.qab.automatic;

import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.integration.CsNavigationHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 存货流程状态机：导航到存货箱 → 主动开箱 → 搬运背包 → 关箱。
 *
 * <p>QShop 在背包空间不足时会拒绝发货，因此购买途中一旦容量不够，
 * 就由本类接管：把主背包（slot 9..35）里的东西搬进箱子，腾出空间后再继续买。</p>
 *
 * <h3>搬运范式</h3>
 * <p>借鉴 auto-sail 的 {@code clickSlot} 双击搬运：拿起源格整堆 → 放进箱子空格。
 * 关键约束（均已实现）：</p>
 * <ul>
 *   <li><b>每 tick 只搬一格</b>并带冷却，一次性搬 27 格会被服务端判定异常；</li>
 *   <li><b>syncId 必须实时取</b>，不能缓存 —— 箱子重开后会变；</li>
 *   <li>容器 GUI 里背包区起始下标 = 容器格数（27/54），不是固定值。</li>
 * </ul>
 *
 * <h3>箱满顺延</h3>
 * <p>当前箱子放不下时，按 {@link QabConfig#getStashPositions()} 的顺序切到下一个存货点，
 * 全部试完仍放不下则以 {@link Result#ALL_FULL} 结束。不做世界扫描。</p>
 *
 * <h3>维度匹配</h3>
 * <p>只使用位于玩家<b>当前维度</b>的存货点：别的维度里的箱子够不着，同一组坐标在本维度
 * 又是另一个方块。全部点位都不在本维度时以 {@link Result#WRONG_DIMENSION} 结束；
 * 流程途中玩家换维度则改判到新维度里的点位。</p>
 *
 * <p><b>Baritone 是全局唯一资源</b>：本类导航期间，购买导航必须处于停止状态。
 * 由 {@link ShoppingRunner} 保证二者不并发。</p>
 */
public final class StashRoutine {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.stash");

    /** 独立导航实例名，与购买导航（"qab"）隔离，避免路径点分组混淆。 */
    private static final String NAV_NAME = "qab-stash";

    /** 对准箱子的最大尝试 tick 数，超时换下一个点位。 */
    private static final int MAX_AIM_TICKS = 80;
    /** 开箱后等待 GUI 出现的最大 tick 数。 */
    private static final int MAX_OPEN_WAIT_TICKS = 60;
    /** 导航到单个存货点的最大 tick 数，防止 Baritone 卡死导致永久挂起。 */
    private static final int MAX_NAV_TICKS = 20 * 120;

    /** 存货流程的运行状态。 */
    public enum Phase {
        /** 未运行。 */
        IDLE,
        /** 正在前往存货点。 */
        NAVIGATING,
        /** 已到达，正在对准并开箱。 */
        OPENING,
        /** 箱子已开，正在搬运。 */
        TRANSFERRING,
        /** 已结束。 */
        DONE
    }

    /** 存货流程的最终结果。 */
    public enum Result {
        /** 搬运完成，已腾出空间。 */
        SUCCESS,
        /** 所有配置的存货点都装不下了。 */
        ALL_FULL,
        /** 没有配置任何存货点。 */
        NO_STASH_CONFIGURED,
        /** 全部点位都无法抵达/开启。 */
        UNREACHABLE,
        /** 背包本来就没东西可搬。 */
        NOTHING_TO_STASH,
        /** 配置的存货点都不在玩家当前维度。 */
        WRONG_DIMENSION
    }

    private final QabConfig config;
    private final List<String> positions;

    private Phase phase = Phase.IDLE;
    private Result result;

    /** 当前尝试的存货点在 {@link #positions} 中的下标。 */
    private int posIndex = -1;
    /** 当前存货点解析出的坐标。 */
    private BlockPos currentChest;
    private String currentDimension;

    private ChunkScannerNavigation nav;

    private int navTicks;
    private int aimTicks;
    /** 已精确对齐并保持的 tick 数，达到 settle 阈值才允许开箱。 */
    private int alignedTicks;
    /** 当前箱子的对准点（形状中心），换点位时重算。 */
    private Vec3d aimTarget;
    private int openWaitTicks;
    private int transferCooldown;

    /** 本轮已成功搬走的格数，用于日志与「是否真的腾出空间」判定。 */
    private int movedSlots;

    /** 因不在当前维度而被跳过的存货点数量。 */
    private int otherDimSkips;
    /** 是否真的尝试过某个存货点（用于区分「都装满了」和「都不在本维度」）。 */
    private boolean triedAnyPosition;
    /** 流程中途玩家换过维度。 */
    private boolean dimensionChanged;

    public StashRoutine(QabConfig config) {
        this.config = config;
        this.positions = config.getStashPositions();
    }

    public Phase getPhase() {
        return phase;
    }

    public Result getResult() {
        return result;
    }

    public boolean isFinished() {
        return phase == Phase.DONE;
    }

    public int getMovedSlots() {
        return movedSlots;
    }

    /** 当前正在使用的存货点坐标，未开始时为 null。 */
    public BlockPos getCurrentChest() {
        return currentChest;
    }

    /**
     * 启动存货流程。
     *
     * @param client 客户端
     * @return true 表示已开始；false 表示无需/无法开始（结果见 {@link #getResult()}）
     */
    public boolean start(MinecraftClient client) {
        if (positions.isEmpty()) {
            finish(Result.NO_STASH_CONFIGURED);
            return false;
        }
        if (client.player == null || countMovableSlots(client.player) == 0) {
            finish(Result.NOTHING_TO_STASH);
            return false;
        }
        posIndex = -1;
        movedSlots = 0;
        otherDimSkips = 0;
        triedAnyPosition = false;
        dimensionChanged = false;
        return advanceToNextStash(client);
    }

    /**
     * 每 tick 推进状态机。由 {@link ShoppingRunner} 调用。
     *
     * @param client 客户端
     */
    public void tick(MinecraftClient client) {
        if (phase == Phase.IDLE || phase == Phase.DONE) return;
        if (client.player == null || client.world == null) return;

        // 维度守卫：玩家中途换了维度，当前箱子坐标在新维度里是另一个方块，
        // 继续走位/开箱只会点到无关方块。改判到下一个（本维度的）存货点。
        if (currentDimension != null && !CsNavigationHelper.inDimension(client, currentDimension)) {
            LOGGER.warn("Player left dimension {} during stash routine, switching stash point.",
                    currentDimension);
            if (nav != null && nav.isActive()) nav.stop();
            if (phase == Phase.TRANSFERRING) closeScreen(client);
            // 旧维度里试过的点位不再算数，收尾原因应归到维度而非「箱子都满了」
            dimensionChanged = true;
            triedAnyPosition = false;
            tryNextOrFail(client, Result.WRONG_DIMENSION);
            return;
        }

        switch (phase) {
            case NAVIGATING -> tickNavigating(client);
            case OPENING -> tickOpening(client);
            case TRANSFERRING -> tickTransferring(client);
            default -> {
            }
        }
    }

    /** 中止流程并清理导航。 */
    public void abort() {
        if (nav != null && nav.isActive()) {
            nav.stop();
        }
        phase = Phase.DONE;
        if (result == null) result = Result.UNREACHABLE;
    }

    // ==================== 各阶段 ====================

    private void tickNavigating(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        // 已经站得够近就不必等导航结束（Baritone 可能停在稍远处）
        double reach = config.getClickReachDist();
        if (BlockAimHelper.reachedForInteraction(client, player, currentChest, reach)) {
            if (nav != null && nav.isActive()) nav.stop();
            enterOpening();
            return;
        }

        if (nav != null && !nav.isActive()) {
            // 导航自行结束（队列空）但距离仍不够：可能是 Baritone 找不到路
            LOGGER.warn("Stash nav ended but still {} blocks away from {}.",
                    String.format("%.1f", BlockAimHelper.distanceTo(player, currentChest)),
                    currentChest);
            tryNextOrFail(client, Result.UNREACHABLE);
            return;
        }

        if (++navTicks >= MAX_NAV_TICKS) {
            LOGGER.warn("Stash nav to {} timed out after {} ticks.", currentChest, MAX_NAV_TICKS);
            if (nav != null) nav.stop();
            tryNextOrFail(client, Result.UNREACHABLE);
        }
    }

    private void tickOpening(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        // 容器 GUI 已经打开 → 进入搬运
        if (isContainerScreen(client)) {
            phase = Phase.TRANSFERRING;
            transferCooldown = 0;
            LOGGER.info("Stash chest opened at {}.", currentChest);
            return;
        }

        // 已经发过右键，等 GUI 出现
        if (openWaitTicks > 0) {
            if (++openWaitTicks >= MAX_OPEN_WAIT_TICKS) {
                LOGGER.warn("Opening chest at {} timed out (no container GUI).", currentChest);
                tryNextOrFail(client, Result.UNREACHABLE);
            }
            return;
        }

        // 与购买告示牌同一套对准逻辑：形状中心 + 限速转头 + 主动发朝向包 + 准星射线校验。
        // 此时导航已停（见 tickNavigating），Baritone 不会再抢改朝向。
        double reach = config.getClickReachDist();
        if (aimTarget == null) {
            aimTarget = BlockAimHelper.aimPoint(client.world, currentChest);
        }
        boolean aligned = BlockAimHelper.stepLookAt(player, aimTarget, config.getAimDegPerTick());

        BlockHitResult hit = null;
        if (aligned) {
            BlockAimHelper.syncRotation(player);
            alignedTicks++;
            if (alignedTicks > config.getAimSettleTicks()) {
                hit = BlockAimHelper.crosshairHit(client, player, currentChest, reach);
            }
        } else {
            alignedTicks = 0;
        }

        if (hit == null) {
            if (aimTicks % 20 == 0) {
                LOGGER.debug("Aiming at stash chest {}: {}", currentChest,
                        BlockAimHelper.describeAim(client, player, currentChest, null));
            }
            if (++aimTicks >= MAX_AIM_TICKS) {
                LOGGER.warn("Cannot aim at stash chest at {} after {} ticks.",
                        currentChest, MAX_AIM_TICKS);
                tryNextOrFail(client, Result.UNREACHABLE);
            }
            return;
        }

        // 主动右键开箱。auto-sail 是被动等 GUI 出现，这里必须自己开。
        if (client.interactionManager != null) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
            openWaitTicks = 1;
            LOGGER.info("Sent open-chest interaction at {} (side={}).", currentChest, hit.getSide());
        }
    }

    private void tickTransferring(MinecraftClient client) {
        // 玩家/服务器把界面关了 → 视为本箱结束
        if (!isContainerScreen(client)) {
            LOGGER.info("Container screen closed during transfer at {}, moved {} slot(s).",
                    currentChest, movedSlots);
            concludeChest(client);
            return;
        }

        if (transferCooldown > 0) {
            transferCooldown--;
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        int containerSize = containerSlotCount(client, handler);
        if (containerSize <= 0) {
            LOGGER.warn("Unexpected container layout at {}, aborting this chest.", currentChest);
            closeScreen(client);
            concludeChest(client);
            return;
        }

        // 容器 GUI 中：0..containerSize-1 是箱子，之后 27 格是主背包，再 9 格是快捷栏。
        // 只搬主背包，快捷栏留给玩家（与 auto-sail 一致）。
        int invStart = containerSize;
        int invEnd = containerSize + InventoryCapacityCalculator.MAIN_INV_SIZE;

        for (int slot = invStart; slot < invEnd && slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || isKeepItem(stack)) continue;

            int dest = findChestSlot(handler, containerSize, stack);
            if (dest < 0) {
                // 这个箱子塞不下了 → 顺延到下一个存货点
                LOGGER.info("Stash chest at {} is full after {} slot(s), trying next.",
                        currentChest, movedSlots);
                closeScreen(client);
                tryNextOrFail(client, Result.ALL_FULL);
                return;
            }

            int syncId = handler.syncId; // 必须实时取，箱子重开后会变
            click(client, syncId, slot, SlotActionType.PICKUP);
            click(client, syncId, dest, SlotActionType.PICKUP);
            movedSlots++;
            transferCooldown = config.getStashTransferDelayTicks();
            return; // 每 tick 只搬一格
        }

        // 没有可搬的了
        LOGGER.info("Stash transfer complete at {}, moved {} slot(s).", currentChest, movedSlots);
        closeScreen(client);
        concludeChest(client);
    }

    // ==================== 辅助 ====================

    /**
     * 本箱处理结束：若已腾出空间则成功收尾，否则继续下一个点位。
     */
    private void concludeChest(MinecraftClient client) {
        if (InventoryCapacityCalculator.emptySlots(client.player) > 0) {
            finish(Result.SUCCESS);
        } else {
            tryNextOrFail(client, Result.ALL_FULL);
        }
    }

    /**
     * 切换到下一个存货点；没有更多点位时以给定结果收尾。
     */
    private void tryNextOrFail(MinecraftClient client, Result failResult) {
        if (!advanceToNextStash(client)) {
            // advanceToNextStash 在没有更多点位时已置 DONE，这里覆盖为更准确的原因
            if (result == null || result == Result.UNREACHABLE) {
                result = failResult;
            }
            phase = Phase.DONE;
            LOGGER.info("Stash routine finished: {} (moved {} slot(s)).", result, movedSlots);
        }
    }

    /**
     * 前进到下一个可用存货点并开始导航。
     *
     * @return true 表示已开始导航；false 表示没有更多点位
     */
    private boolean advanceToNextStash(MinecraftClient client) {
        while (++posIndex < positions.size()) {
            String raw = positions.get(posIndex);
            CsNavigationHelper.ParsedPos pp = CsNavigationHelper.parsePosition(raw);
            if (pp == null) {
                LOGGER.warn("Skipping unparseable stash position: {}", raw);
                continue;
            }
            // 维度匹配：别的维度里的箱子够不着，直接跳过（自动跨维度寻路不可靠）
            if (!CsNavigationHelper.inDimension(client, pp.dimensionId)) {
                otherDimSkips++;
                LOGGER.debug("Skipping stash position in another dimension: {}", raw);
                continue;
            }

            currentChest = new BlockPos(pp.x, pp.y, pp.z);
            currentDimension = pp.dimensionId;
            navTicks = 0;
            aimTicks = 0;
            alignedTicks = 0;
            aimTarget = null;
            openWaitTicks = 0;

            triedAnyPosition = true;

            // 已经在旁边就直接开箱，省一次导航
            if (client.player != null
                    && BlockAimHelper.reachedForInteraction(client, client.player, currentChest,
                            config.getClickReachDist())) {
                enterOpening();
                return true;
            }

            nav = StashNavHolder.get();
            nav.clear();
            nav.enqueue(pp.x, pp.y, pp.z, pp.dimensionId);
            nav.start();
            phase = Phase.NAVIGATING;
            LOGGER.info("Stash routine navigating to {} ({}).", currentChest, currentDimension);
            return true;
        }

        Result fallback = result;
        if (fallback == null) {
            // 一个点位都没试过、全被维度过滤掉了 → 是维度问题而不是箱子满了
            fallback = (!triedAnyPosition && (otherDimSkips > 0 || dimensionChanged))
                    ? Result.WRONG_DIMENSION : Result.ALL_FULL;
        }
        finish(fallback);
        return false;
    }

    private void enterOpening() {
        phase = Phase.OPENING;
        aimTicks = 0;
        alignedTicks = 0;
        openWaitTicks = 0;
    }

    private void finish(Result r) {
        this.result = r;
        this.phase = Phase.DONE;
        LOGGER.info("Stash routine finished: {} (moved {} slot(s)).", r, movedSlots);
    }

    /** 当前界面是否为可搬运的容器界面。 */
    private static boolean isContainerScreen(MinecraftClient client) {
        return client.currentScreen instanceof GenericContainerScreen
                || client.currentScreen instanceof ShulkerBoxScreen;
    }

    /**
     * 容器区域的格子数（箱子 27 / 大箱子 54 / 潜影盒 27）。
     *
     * <p>用 handler 的总格数减去玩家背包 36 格推算，避免对不同 handler 类型做特判。</p>
     *
     * @return 容器格数；布局异常时返回 -1
     */
    private static int containerSlotCount(MinecraftClient client, ScreenHandler handler) {
        if (handler == null) return -1;
        int total = handler.slots.size();
        int containerSize = total - 36; // 27 主背包 + 9 快捷栏
        return containerSize > 0 ? containerSize : -1;
    }

    /**
     * 在箱子区域找一个能放下该物品的格子：优先可合并的同类未满堆，其次空格。
     *
     * @return 目标格下标；放不下返回 -1
     */
    private static int findChestSlot(ScreenHandler handler, int containerSize, ItemStack stack) {
        // 先找能合并的同类堆，尽量少占格子
        for (int i = 0; i < containerSize; i++) {
            ItemStack dst = handler.getSlot(i).getStack();
            if (!dst.isEmpty()
                    && ItemStack.canCombine(dst, stack)
                    && dst.getCount() < dst.getMaxCount()) {
                return i;
            }
        }
        for (int i = 0; i < containerSize; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 该物品是否配置为保留在背包中不搬走。 */
    private boolean isKeepItem(ItemStack stack) {
        List<String> keep = config.getStashKeepItems();
        if (keep.isEmpty()) return false;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        return keep.contains(id);
    }

    /** 主背包中可搬走的格数（排除保留物品）。 */
    private int countMovableSlots(ClientPlayerEntity player) {
        int n = 0;
        for (int i = InventoryCapacityCalculator.MAIN_INV_START;
             i <= InventoryCapacityCalculator.MAIN_INV_END; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isKeepItem(stack)) n++;
        }
        return n;
    }

    private static void click(MinecraftClient client, int syncId, int slot, SlotActionType type) {
        if (client.interactionManager == null || client.player == null) return;
        client.interactionManager.clickSlot(syncId, slot, 0, type, client.player);
    }

    private static void closeScreen(MinecraftClient client) {
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
    }

    /**
     * 存货导航实例持有者：独立于购买导航，避免两边队列互相清空。
     */
    private static final class StashNavHolder {
        private static volatile ChunkScannerNavigation instance;

        static ChunkScannerNavigation get() {
            ChunkScannerNavigation nav = instance;
            if (nav == null) {
                synchronized (StashNavHolder.class) {
                    nav = instance;
                    if (nav == null) {
                        nav = com.billy65536.chunkscanner.api.NavigationApi.createNavigation(NAV_NAME);
                        com.billy65536.chunkscanner.api.NavigationApi.manageTick(nav);
                        instance = nav;
                        LOGGER.info("QAB stash navigation instance created.");
                    }
                }
            }
            return nav;
        }
    }
}
