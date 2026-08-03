package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.core.navigation.NavigationCondition;
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

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 导航到达条件 + 自动购买副作用。
 *
 * <p>作为 {@link NavigationCondition} 注入 {@code ChunkScannerNavigation}：
 * <ul>
 *   <li>{@link #isSatisfied(MinecraftClient)} 判定玩家准星是否正指向该告示牌且距离足够近；</li>
 *   <li>一旦可点击，立即执行购买副作用（左键点击告示牌，延时发送配置命令），并返回 {@code true}
 *       让导航队列弹出该目标、推进下一个；</li>
 *   <li>幂等保护：同一目标只购买一次，避免每帧重复触发。</li>
 * </ul>
 *
 * <h3>购买流程</h3>
 * <ol>
 *   <li>左键点击告示牌（等价于玩家手动左键，服务器识别为商店交互）；</li>
 *   <li>等待 {@code config.buyDelayMs} 毫秒（默认 500）；</li>
 *   <li>发送 {@code config.buyCommand}（{@code {count}} 替换为购买总量）。</li>
 * </ol>
 */
public final class QShopBuyCondition implements NavigationCondition {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.buy");

    private final BlockPos signPos;
    private final int buyCount;
    private final QabConfig config;
    private final AtomicBoolean triggered = new AtomicBoolean(false);

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

        // 准星必须指向方块目标
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) return false;
        BlockPos target = hit.getBlockPos();
        if (!target.equals(signPos)) return false;

        // 必须是告示牌（QShop 商店）
        BlockEntity be = client.world.getBlockEntity(target);
        if (!(be instanceof SignBlockEntity)) return false;

        // 距离阈值
        Vec3d eye = client.player.getEyePos();
        Vec3d signCenter = Vec3d.ofCenter(signPos);
        if (eye.distanceTo(signCenter) > config.getClickReachDist()) return false;

        // 满足条件：执行购买副作用（一次性）
        if (triggered.compareAndSet(false, true)) {
            performPurchase(client);
        }
        return true;
    }

    /**
     * 执行自动购买：左键点击告示牌，延时发送购买命令。
     */
    private void performPurchase(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        // 1. 左键点击告示牌（等价于玩家手动左键，会向服务器发送攻击包）
        try {
            client.interactionManager.attackBlock(signPos, Direction.UP);
            LOGGER.info("QAB auto-clicked QShop sign at {} for {} item(s)", signPos, buyCount);
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
                            if(command.startsWith("/")) {
                                p.networkHandler.sendChatCommand(command);
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
