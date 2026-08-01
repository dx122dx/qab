package com.billy65536.qab;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QShopAutoBuyMod implements ClientModInitializer {
    public static final String MOD_ID = "qshop-auto-buy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("QShopAutoBuy mod initializing...");

        // 注册命令：/qab select db|list、/qab plan
        QabCommands.register();
        LOGGER.info("Commands registered. Use /qab select db/list, then /qab plan.");
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}
