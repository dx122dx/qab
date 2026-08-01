package com.billy65536.qab;

import com.billy65536.qab.loader.QShopDbLoader;
import com.billy65536.qab.planning.ShoppingPlanner;
import com.billy65536.qab.planning.model.ShopExportData;
import com.billy65536.qab.planning.model.ShoppingList;
import com.billy65536.qab.planning.model.ShoppingPlan;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * QAB 命令注册：/qab select db|list、/qab plan。
 */
public class QabCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/commands");

    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
    private static final Path CS_EXPORT_DIR = GAME_DIR.resolve("chunkscanner").resolve("export");
    private static final Path QAB_LIST_DIR = GAME_DIR.resolve("qab").resolve("list");

    private static final DateTimeFormatter PLAN_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    static Path selectedDb = null;
    static Path selectedList = null;

    // ---- auto-complete: .zip basenames in chunkscanner/export/ ----
    private static final SuggestionProvider<FabricClientCommandSource> DB_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>();
                if (Files.isDirectory(CS_EXPORT_DIR)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(CS_EXPORT_DIR, "*.zip")) {
                        for (Path p : ds) {
                            String n = p.getFileName().toString();
                            names.add(n.substring(0, n.length() - 4)); // strip ".zip"
                        }
                    } catch (IOException ignored) {
                    }
                }
                return CommandSource.suggestMatching(names, builder);
            };

    // ---- auto-complete: .json basenames in qab/list/ ----
    private static final SuggestionProvider<FabricClientCommandSource> LIST_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>();
                if (Files.isDirectory(QAB_LIST_DIR)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(QAB_LIST_DIR, "*.json")) {
                        for (Path p : ds) {
                            String n = p.getFileName().toString();
                            names.add(n.substring(0, n.length() - 5)); // strip ".json"
                        }
                    } catch (IOException ignored) {
                    }
                }
                return CommandSource.suggestMatching(names, builder);
            };

    // ---- register ----
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = literal("qab");

            var select = literal("select")
                    .then(literal("db")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(DB_SUGGESTIONS)
                                    .executes(QabCommands::execSelectDb)))
                    .then(literal("list")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(LIST_SUGGESTIONS)
                                    .executes(QabCommands::execSelectList)));

            var plan = literal("plan")
                    .executes(ctx -> execGeneratePlan(ctx, null))
                    .then(argument("name", StringArgumentType.string())
                            .executes(ctx -> execGeneratePlan(ctx,
                                    StringArgumentType.getString(ctx, "name"))));

            root.then(select);
            root.then(plan);

            dispatcher.register(root);
            LOGGER.info("Registered /qab commands");
        });
    }

    // ---- select db ----
    private static int execSelectDb(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path candidate = CS_EXPORT_DIR.resolve(file + ".zip");
        if (Files.exists(candidate)) {
            selectedDb = candidate;
        } else {
            Path direct = Path.of(file);
            if (Files.exists(direct)) {
                selectedDb = direct;
            } else {
                ctx.getSource().sendError(Text.literal("File not found: " + file));
                return 0;
            }
        }
        ctx.getSource().sendFeedback(Text.literal("Selected DB: " + selectedDb.getFileName()));
        LOGGER.info("Selected DB: {}", selectedDb);
        return 1;
    }

    // ---- select list ----
    private static int execSelectList(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path candidate = QAB_LIST_DIR.resolve(file + ".json");
        if (Files.exists(candidate)) {
            selectedList = candidate;
        } else {
            Path direct = Path.of(file);
            if (Files.exists(direct)) {
                selectedList = direct;
            } else {
                ctx.getSource().sendError(Text.literal("File not found: " + file));
                return 0;
            }
        }
        ctx.getSource().sendFeedback(Text.literal("Selected list: " + selectedList.getFileName()));
        LOGGER.info("Selected list: {}", selectedList);
        return 1;
    }

    // ---- plan generator ----
    private static int execGeneratePlan(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (selectedDb == null) {
            ctx.getSource().sendError(Text.literal("No DB selected. Use /qab select db first."));
            return 0;
        }
        if (selectedList == null) {
            ctx.getSource().sendError(Text.literal("No list selected. Use /qab select list first."));
            return 0;
        }

        String planName = (name == null || name.isBlank())
                ? "plan-" + LocalDateTime.now().format(PLAN_TIME)
                : name;
        if (!planName.endsWith(".json")) {
            planName += ".json";
        }

        try {
            Files.createDirectories(QAB_LIST_DIR);

            ShoppingList list = loadShoppingList(selectedList);
            if (list == null || list.getItems() == null || list.getItems().isEmpty()) {
                ctx.getSource().sendError(Text.literal("Failed to load shopping list: " + selectedList));
                return 0;
            }

            ShopExportData export = QShopDbLoader.load(selectedDb);
            ShoppingPlan plan = ShoppingPlanner.generatePlan(list, export);

            Path outPath = QAB_LIST_DIR.resolve(planName);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(plan);
            Files.writeString(outPath, json, StandardCharsets.UTF_8);

            ctx.getSource().sendFeedback(Text.literal(
                    "Plan generated: " + outPath.getFileName()
                            + " (" + plan.getPlan().size() + " entries, total cost "
                            + plan.getTotalCost() + ")"));
            LOGGER.info("Plan generated: {} ({} entries)", outPath, plan.getPlan().size());
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to generate plan", e);
            ctx.getSource().sendError(Text.literal("Failed to generate plan: " + e.getMessage()));
            return 0;
        }
    }

    private static ShoppingList loadShoppingList(Path path) {
        if (!Files.exists(path)) return null;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return new Gson().fromJson(json, ShoppingList.class);
        } catch (Exception e) {
            LOGGER.error("Failed to load shopping list: {}", path, e);
            return null;
        }
    }
}
