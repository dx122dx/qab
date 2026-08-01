package com.billy65536.qab;

import com.billy65536.qab.loader.QShopDbLoader;
import com.billy65536.qab.planning.ShoppingPlanner;
import com.billy65536.qab.planning.model.ShopExportData;
import com.billy65536.qab.planning.model.ShoppingList;
import com.billy65536.qab.planning.model.ShoppingPlan;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class QShopAutoBuyMod implements ModInitializer {
    public static final String MOD_ID = "qshop-auto-buy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("QShopAutoBuy mod initializing...");

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("qab");
        Path shopData = configDir.resolve("shopping.json");

        // 1. 加载购物清单
        ShoppingList list = loadShoppingList(shopData);
        if (list == null || list.getItems() == null || list.getItems().isEmpty()) {
            LOGGER.warn("No shopping list found or list is empty: {}", shopData);
            return;
        }
        LOGGER.info("Loaded shopping list: {}{}",
                list.getName(),
                list.getDescription() != null ? " - " + list.getDescription() : "");

        // 2. 从chunkscanner ZIP导出中加载QShop数据
        //    使用metadata.json → FactoryRegistry.get(databaseType).create(...)
        //    对BinaryChunkDb实现类无硬依赖
        ShopExportData export = QShopDbLoader.loadDefault();
        if (export == null || export.isEmpty()) {
            LOGGER.warn("No QShop data loaded from chunkscanner export");
            return;
        }
        LOGGER.info("Loaded {} QShop entries from chunkscanner", export.size());

        // 3. 生成购物计划
        ShoppingPlan plan = ShoppingPlanner.generatePlan(list, export);
        LOGGER.info("Plan generated: {} entries, total cost: {}",
                plan.getPlan().size(), plan.getTotalCost());
        // TODO：将计划导出/保存到文件
    }

    private static ShoppingList loadShoppingList(Path path) {
        if (!Files.exists(path)) {
            LOGGER.warn("Shopping list file not found: {}", path);
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return new Gson().fromJson(json, ShoppingList.class);
        } catch (IOException e) {
            LOGGER.error("Failed to load shopping list: {}", path, e);
            return null;
        }
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}
