package com.billy65536.qab.automatic;

import net.minecraft.util.math.BlockPos;

/**
 * 一个待执行的购买任务：去某个告示牌买某物若干个。
 *
 * <p>由 {@link ShoppingRunner} 维护的待办队列元素。与 {@code PlanEntry} 的区别在于
 * 它是<b>运行时可变</b>的 —— 部分购买后剩余量会构造出一个新的 BuyTask 重新入队。</p>
 */
public final class BuyTask {

    private final String dimensionId;
    private final BlockPos signPos;
    private final String itemId;
    private final int amount;

    /** 该任务已被拆分重试的次数，用于防止无限循环。 */
    private final int retryCount;

    public BuyTask(String dimensionId, BlockPos signPos, String itemId, int amount) {
        this(dimensionId, signPos, itemId, amount, 0);
    }

    public BuyTask(String dimensionId, BlockPos signPos, String itemId, int amount, int retryCount) {
        this.dimensionId = dimensionId;
        this.signPos = signPos;
        this.itemId = itemId;
        this.amount = amount;
        this.retryCount = retryCount;
    }

    public String getDimensionId() {
        return dimensionId;
    }

    public BlockPos getSignPos() {
        return signPos;
    }

    public String getItemId() {
        return itemId;
    }

    /** 本次要购买的数量。 */
    public int getAmount() {
        return amount;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /**
     * 基于本任务构造一个「剩余量」任务，用于部分购买后回插队列。
     *
     * @param remaining 剩余待购买数量
     * @return 新任务，重试计数 +1
     */
    public BuyTask withRemaining(int remaining) {
        return new BuyTask(dimensionId, signPos, itemId, remaining, retryCount + 1);
    }

    @Override
    public String toString() {
        return String.format("BuyTask{%s x%d @ %s%s, retry=%d}",
                itemId, amount, dimensionId, signPos, retryCount);
    }
}
