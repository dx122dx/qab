package com.billy65536.qab;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.qab.automatic.ShoppingRunner;

public class QShopAutoBuyMod implements ClientModInitializer {
    public static final String MOD_ID = "qshop-auto-buy";
    public static final Logger LOGGER = LoggerFactory.getLogger("qab");

    @Override
    public void onInitializeClient() {
        LOGGER.info("QShopAutoBuy mod initializing...");

        // 注册命令：/qab select db|list、/qab plan、/qab nav、/qab stash
        QabCommands.register();
        LOGGER.info("Commands registered. Use /qab select db/list, then /qab plan.");

        // 驱动购买编排器。导航实例本身由 chunkscanner 托管 tick，
        // 但「部分购买回插队列 / 触发存货」这层编排是 QAB 自己的状态机，需自行驱动。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                ShoppingRunner.getInstance().tick(client);
            } catch (Exception e) {
                // 单次 tick 异常不应中断游戏循环，但要停掉流程避免反复刷错
                LOGGER.error("Shopping runner tick failed, stopping.", e);
                ShoppingRunner.getInstance().stop();
            }
        });
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}
