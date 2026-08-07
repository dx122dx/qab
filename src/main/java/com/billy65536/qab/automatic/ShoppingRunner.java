package com.billy65536.qab.automatic;

import com.billy65536.chunkscanner.core.navigation.ChunkScannerNavigation;
import com.billy65536.chunkscanner.core.navigation.NavigationEntry;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.integration.CsNavigationHelper;
import com.billy65536.qab.integration.QShopBuyCondition;
import com.billy65536.qab.planner.model.PlanEntry;
import com.billy65536.qab.planner.model.ShoppingPlan;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 购买流程编排器：QAB 自己持有待办队列，一次只向 chunkscanner 投递<b>一个</b>目标。
 *
 * <h3>为什么不一次性把全部目标灌进导航队列</h3>
 * <p>「买不完就把剩余量回插队列」要求在导航进行中动态改队列，而
 * {@link ChunkScannerNavigation#clear()} 等同于 {@code stop()}（会清空并中止），
 * 没有「移除单个目标」或「插队」的 API。若一次性灌入，中途就无法调整。</p>
 *
 * <p>改为单目标投递后：QAB 持有 {@link #pending} 队列，导航里永远只有一个目标。
 * 该目标完成（{@code nav.isActive()} 转 false）后再投下一个。这样既能在买不完时
 * 把剩余量重新排进 {@link #pending}，也能在中途插入存货流程而不打乱主队列，
 * 同时天然满足「Baritone 全局唯一，同一时刻只有一个导航活动」的架构约束。</p>
 *
 * <h3>容量不足时的处理</h3>
 * <p>QShop 背包不足会拒绝发货，所以到店后先算容量：</p>
 * <ul>
 *   <li>装得下 → 全额购买；</li>
 *   <li>只装得下一部分 → 买这部分，剩余量回插队首，随后触发存货，存完回来接着买；</li>
 *   <li>一个都装不下 → 不下单，整单回插队首，直接去存货。</li>
 * </ul>
 */
public final class ShoppingRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.runner");

    /** 同一个目标允许被拆分重试的最大次数，防止存货腾不出空间时无限循环。 */
    private static final int MAX_RETRY_PER_TASK = 8;

    /** 投递目标后等待导航真正启动的宽限 tick 数。 */
    private static final int NAV_START_GRACE_TICKS = 5;

    /**
     * 购买命令发出后的结算等待 tick 数。
     *
     * <p>服务器发货需要时间。若立刻去算下一单的容量，读到的还是发货前的背包状态，
     * 会高估可用空间，导致下一单被 QShop 拒发。</p>
     */
    private static final int SETTLE_TICKS = 20;

    private static final ShoppingRunner INSTANCE = new ShoppingRunner();

    public static ShoppingRunner getInstance() {
        return INSTANCE;
    }

    private final Deque<BuyTask> pending = new ArrayDeque<>();

    private QabConfig config;
    private boolean running;

    /** 当前投递给导航的任务。 */
    private BuyTask currentTask;
    private QShopBuyCondition currentCondition;
    private int navGrace;

    /** 购买命令发出后的结算倒计时，>0 时暂不处理结果。 */
    private int settleTicks;

    /** 存货子流程，非 null 表示正在存货（此时购买导航已停止）。 */
    private StashRoutine stash;

    // ---- 统计 ----
    private int totalTasks;
    private int completedTasks;
    private int boughtItems;
    private int skippedTasks;

    private ShoppingRunner() {
    }

    /**
     * 载入计划并开始执行。
     *
     * @param plan   购物计划（必须为可购买格式，调用方先校验 {@link ShoppingPlan#isBuyable()}）
     * @param config QAB 配置
     * @return 成功入队的任务数
     */
    public int start(ShoppingPlan plan, QabConfig config) {
        stop();

        this.config = config;
        pending.clear();

        for (PlanEntry entry : plan.getPlan()) {
            CsNavigationHelper.ParsedPos pp = CsNavigationHelper.parsePosition(entry.getPosition());
            if (pp == null) {
                LOGGER.warn("Skipping plan entry with unparseable position: {}", entry.getPosition());
                continue;
            }
            int amount = entry.getTotal();
            if (amount <= 0) continue;

            pending.addLast(new BuyTask(pp.dimensionId,
                    new BlockPos(pp.x, pp.y, pp.z), entry.getItemId(), amount));
        }

        totalTasks = pending.size();
        completedTasks = 0;
        boughtItems = 0;
        skippedTasks = 0;

        if (pending.isEmpty()) {
            return 0;
        }

        running = true;
        LOGGER.info("Shopping runner started with {} task(s).", totalTasks);
        dispatchNext(MinecraftClient.getInstance());
        return totalTasks;
    }

    /** 中止执行并清空队列。 */
    public void stop() {
        if (stash != null) {
            stash.abort();
            stash = null;
        }
        ChunkScannerNavigation nav = CsNavigationHelper.navigationIfPresent();
        if (nav != null && nav.isActive()) {
            nav.stop();
        }
        pending.clear();
        currentTask = null;
        currentCondition = null;
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    /** 剩余待办任务数（含当前正在执行的那个）。 */
    public int remaining() {
        return pending.size() + (currentTask != null ? 1 : 0);
    }

    public int getBoughtItems() {
        return boughtItems;
    }

    /**
     * 每 tick 驱动。由 {@code QShopAutoBuyMod} 的客户端 tick 事件调用。
     *
     * @param client 客户端
     */
    public void tick(MinecraftClient client) {
        if (!running || client.player == null || client.world == null) return;

        // 存货子流程优先：期间购买导航保持停止，避免抢占 Baritone
        if (stash != null) {
            stash.tick(client);
            if (stash.isFinished()) {
                onStashFinished(client);
            }
            return;
        }

        if (currentTask == null) {
            dispatchNext(client);
            return;
        }

        QShopBuyCondition cond = currentCondition;

        // 已到店：导航已把目标出队并取消 Baritone，
        // 「对准 → 点击 → 发购买命令」这段时序由本编排器逐 tick 驱动。
        if (cond != null && cond.isArrived()) {
            boolean hadPending = cond.hasPendingCommand();
            cond.tick(client);
            if (hadPending && !cond.hasPendingCommand()) {
                // 命令刚发出，等服务器发货后再评估容量
                settleTicks = cond.getBoughtAmount() > 0 ? SETTLE_TICKS : 0;
            }
            // 还在对准，或购买命令尚未发出，都不能推进到下一个目标
            if (!cond.isResolved() || cond.hasPendingCommand()) {
                return;
            }
            if (settleTicks > 0) {
                settleTicks--;
                return;
            }
            handleTaskOutcome(client);
            return;
        }

        // 还没到店导航就结束了：目标不可达，跳过以免卡死
        ChunkScannerNavigation nav = CsNavigationHelper.navigationIfPresent();
        if (nav != null && !nav.isActive()) {
            if (navGrace > 0) {
                navGrace--;
                return;
            }
            LOGGER.warn("Navigation ended without reaching {}, skipping.", currentTask.getSignPos());
            notifyPlayer(client, Text.translatable("qab.msg.stash_target_unreachable",
                    formatPos(currentTask.getSignPos())).formatted(Formatting.YELLOW));
            skippedTasks++;
            currentTask = null;
            currentCondition = null;
            dispatchNext(client);
        }
    }

    // ==================== 内部 ====================

    /** 取出下一个任务投递给导航；队列空则收尾。 */
    private void dispatchNext(MinecraftClient client) {
        BuyTask task = pending.pollFirst();
        if (task == null) {
            finishAll(client);
            return;
        }

        currentTask = task;
        currentCondition = new QShopBuyCondition(task, config);
        navGrace = NAV_START_GRACE_TICKS;
        settleTicks = 0;

        ChunkScannerNavigation nav = CsNavigationHelper.navigation();
        nav.clear();
        BlockPos pos = task.getSignPos();
        nav.enqueue(new NavigationEntry(task.getDimensionId(), pos.getX(), pos.getY(), pos.getZ()),
                currentCondition);
        nav.start();

        LOGGER.info("Dispatched {} ({} remaining).", task, pending.size());
    }

    /** 处理当前任务的执行结果。 */
    private void handleTaskOutcome(MinecraftClient client) {
        BuyTask task = currentTask;
        QShopBuyCondition cond = currentCondition;
        currentTask = null;
        currentCondition = null;

        ChunkScannerNavigation nav = CsNavigationHelper.navigationIfPresent();
        if (nav != null && nav.isActive()) {
            nav.stop();
        }

        int bought = cond.getBoughtAmount();
        int remaining = cond.getRemainingAmount();
        boughtItems += bought;

        if (cond.isAimFailed()) {
            // 到店了却始终点不到牌子（被遮挡 / 牌子已不存在），给玩家一条明确提示
            notifyPlayer(client, Text.translatable("qab.msg.buy_aim_failed",
                    formatPos(task.getSignPos())).formatted(Formatting.YELLOW));
            skippedTasks++;
            dispatchNext(client);
            return;
        }

        if (bought > 0) {
            LOGGER.info("Bought {} x{} at {} ({} remaining at this shop).",
                    task.getItemId(), bought, task.getSignPos(), remaining);
        }

        if (remaining <= 0) {
            completedTasks++;
            dispatchNext(client);
            return;
        }

        // 还没买完：先把剩余量排回队首，再决定是否去存货
        if (task.getRetryCount() >= MAX_RETRY_PER_TASK) {
            LOGGER.warn("Task {} exceeded retry limit, skipping {} remaining item(s).",
                    task, remaining);
            notifyPlayer(client, Text.translatable("qab.msg.stash_retry_exhausted",
                    task.getItemId(), remaining).formatted(Formatting.YELLOW));
            skippedTasks++;
            dispatchNext(client);
            return;
        }

        pending.addFirst(task.withRemaining(remaining));

        if (cond.isBlockedByCapacity()) {
            if (!config.isStashEnabled()) {
                LOGGER.warn("Inventory full and stash disabled; skipping remaining {} item(s).",
                        remaining);
                notifyPlayer(client, Text.translatable("qab.msg.stash_disabled_full")
                        .formatted(Formatting.RED));
                pending.pollFirst(); // 丢弃刚回插的任务
                skippedTasks++;
                dispatchNext(client);
                return;
            }
            beginStash(client);
        } else {
            // 非容量原因（如超时）导致没买完，直接重试
            dispatchNext(client);
        }
    }

    /** 启动存货子流程。 */
    private void beginStash(MinecraftClient client) {
        notifyPlayer(client, Text.translatable("qab.msg.stash_started")
                .formatted(Formatting.AQUA));

        stash = new StashRoutine(config);
        if (!stash.start(client)) {
            // 未能开始（无点位 / 无东西可搬），立即收尾
            onStashFinished(client);
        }
    }

    /** 存货子流程结束后的处理。 */
    private void onStashFinished(MinecraftClient client) {
        StashRoutine.Result r = stash != null ? stash.getResult() : StashRoutine.Result.UNREACHABLE;
        int moved = stash != null ? stash.getMovedSlots() : 0;
        stash = null;

        switch (r) {
            case SUCCESS -> {
                notifyPlayer(client, Text.translatable("qab.msg.stash_done", moved)
                        .formatted(Formatting.GREEN));
                dispatchNext(client);
            }
            case NOTHING_TO_STASH -> {
                // 背包里全是保留物品却仍然装不下，继续买也没意义
                notifyPlayer(client, Text.translatable("qab.msg.stash_nothing")
                        .formatted(Formatting.RED));
                abortWithReason(client);
            }
            case NO_STASH_CONFIGURED -> {
                notifyPlayer(client, Text.translatable("qab.msg.stash_not_configured")
                        .formatted(Formatting.RED));
                abortWithReason(client);
            }
            case ALL_FULL -> {
                notifyPlayer(client, Text.translatable("qab.msg.stash_all_full", moved)
                        .formatted(Formatting.RED));
                abortWithReason(client);
            }
            default -> {
                notifyPlayer(client, Text.translatable("qab.msg.stash_unreachable")
                        .formatted(Formatting.RED));
                abortWithReason(client);
            }
        }
    }

    /** 因存货失败而中止整个购买流程。 */
    private void abortWithReason(MinecraftClient client) {
        int left = remaining();
        stop();
        notifyPlayer(client, Text.translatable("qab.msg.buy_aborted", left, boughtItems)
                .formatted(Formatting.RED));
    }

    /** 全部任务处理完毕。 */
    private void finishAll(MinecraftClient client) {
        running = false;
        currentTask = null;
        currentCondition = null;
        LOGGER.info("Shopping complete: {}/{} task(s), {} item(s) bought, {} skipped.",
                completedTasks, totalTasks, boughtItems, skippedTasks);
        notifyPlayer(client, Text.translatable("qab.msg.buy_complete",
                completedTasks, totalTasks, boughtItems, skippedTasks)
                .formatted(Formatting.GREEN));
    }

    private static void notifyPlayer(MinecraftClient client, Text text) {
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 估算购买 {@code amount} 个 {@code itemId} 需要的背包容量是否足够。
     *
     * @return 实际可购买数量（0 表示一个都装不下，-1 表示物品 ID 无法解析）
     */
    public static int affordableAmount(MinecraftClient client, String itemId, int amount, QabConfig config) {
        Item item = InventoryCapacityCalculator.resolveItem(itemId);
        if (item == null) return -1;
        int capacity = InventoryCapacityCalculator.capacityFor(
                client.player, item, config.getStashReserveSlots());
        return Math.min(amount, Math.max(0, capacity));
    }
}
