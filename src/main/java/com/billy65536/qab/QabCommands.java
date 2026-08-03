package com.billy65536.qab;

import com.billy65536.chunkscanner.core.db.DbValidationResult;
import com.billy65536.qab.generate.ListGenConfig;
import com.billy65536.qab.generate.SchematicListGenerator;
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
import net.minecraft.util.Formatting;

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
 * QAB 命令注册：/qab select db|list、/qab plan、/qab nav apply、/qab generate list。
 */
public class QabCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/commands");

    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
    private static final Path CS_EXPORT_DIR = GAME_DIR.resolve("chunkscanner").resolve("export");
    private static final Path SCHEMATICS_DIR = GAME_DIR.resolve("schematics");
    private static final Path QAB_DIR = GAME_DIR.resolve("qab");
    private static final Path QAB_LIST_DIR = QAB_DIR.resolve("list");
    private static final Path QAB_PLAN_DIR = QAB_DIR.resolve("plan");

    /** schematic4j 支持的原理图扩展名。 */
    private static final List<String> SCHEMATIC_EXTENSIONS =
            List.of(".litematic", ".schem", ".schematic", ".nbt");

    private static final DateTimeFormatter PLAN_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    static QShopDbLoader selectedDb = null;
    static Path selectedList = null;

    // ---- auto-complete: .zip basenames in chunkscanner/export/ ----
    private static final SuggestionProvider<FabricClientCommandSource> DB_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>();
                if (Files.isDirectory(CS_EXPORT_DIR)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(CS_EXPORT_DIR, "*.zip")) {
                        for (Path p : ds) {
                            String n = p.getFileName().toString();
                            String bn = n.substring(0, n.length() - 4); // strip ".zip"
                            if(bn.indexOf(' ') >= 0) {
                                bn = "\"" + bn + "\"";
                            }
                            names.add(bn);
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
                            String bn = n.substring(0, n.length() - 5); // strip ".json"
                            if(bn.indexOf(' ') >= 0) {
                                bn = "\"" + bn + "\"";
                            }
                            names.add(bn);
                        }
                    } catch (IOException ignored) {
                    }
                }
                return CommandSource.suggestMatching(names, builder);
            };

    // ---- auto-complete: .json basenames in qab/plan/ ----
    private static final SuggestionProvider<FabricClientCommandSource> PLAN_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>();
                if (Files.isDirectory(QAB_PLAN_DIR)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(QAB_PLAN_DIR, "*.json")) {
                        for (Path p : ds) {
                            String n = p.getFileName().toString();
                            String bn = n.substring(0, n.length() - 5); // strip ".json"
                            if (bn.indexOf(' ') >= 0) {
                                bn = "\"" + bn + "\"";
                            }
                            names.add(bn);
                        }
                    } catch (IOException ignored) {
                    }
                }
                return CommandSource.suggestMatching(names, builder);
            };

    // ---- auto-complete: schematic basenames in schematics/ ----
    private static final SuggestionProvider<FabricClientCommandSource> SCHEMATIC_SUGGESTIONS =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>();
                if (Files.isDirectory(SCHEMATICS_DIR)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(SCHEMATICS_DIR)) {
                        for (Path p : ds) {
                            if (!Files.isRegularFile(p)) continue;
                            String n = p.getFileName().toString();
                            String ext = matchedExtension(n);
                            if (ext == null) continue;
                            String bn = n.substring(0, n.length() - ext.length());
                            if (bn.indexOf(' ') >= 0) {
                                bn = "\"" + bn + "\"";
                            }
                            names.add(bn);
                        }
                    } catch (IOException ignored) {
                    }
                }
                return CommandSource.suggestMatching(names, builder);
            };

    /** 返回文件名匹配到的原理图扩展名（含点），不匹配返回 null。 */
    private static String matchedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : SCHEMATIC_EXTENSIONS) {
            if (lower.endsWith(ext)) return ext;
        }
        return null;
    }

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

            var nav = literal("nav")
                    .then(literal("apply")
                            .executes(ctx -> execNavApply(ctx, null))
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(PLAN_SUGGESTIONS)
                                    .executes(ctx -> execNavApply(ctx,
                                            StringArgumentType.getString(ctx, "file")))));

            // /qab generate list <file> [config...]
            // config 格式: key=value [key=value ...]
            var generate = literal("generate")
                    .then(literal("list")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(SCHEMATIC_SUGGESTIONS)
                                    .executes(ctx -> execGenerateList(ctx,
                                            StringArgumentType.getString(ctx, "file"), null))
                                    .then(argument("config", StringArgumentType.greedyString())
                                            .executes(ctx -> execGenerateList(ctx,
                                                    StringArgumentType.getString(ctx, "file"),
                                                    StringArgumentType.getString(ctx, "config"))))));

            root.then(select);
            root.then(plan);
            root.then(nav);
            root.then(generate);

            dispatcher.register(root);
            LOGGER.info("Registered /qab commands");
        });
    }

    // ---- select db ----
    private static int execSelectDb(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path candidate = CS_EXPORT_DIR.resolve(file + ".zip");
        Path direct = Path.of(file);
        Path target = Files.exists(candidate)? candidate: direct;
        if (Files.exists(target)) {
            try {
                selectedDb = new QShopDbLoader(target);
            } catch (Exception e) {
                if (e instanceof IOException) {
                    ctx.getSource().sendError(Text.translatable("db_select_io_error", e.getMessage()));
                } else if(e instanceof IllegalArgumentException) {
                    ctx.getSource().sendError(Text.translatable("db_select_metadata_invalid", e.getMessage()));
                } else {
                    ctx.getSource().sendError(Text.translatable("db_select_unexpected", e.getMessage()));
                }
                LOGGER.warn("Failed to select DB '{}': {}", target.toString(), e);
                selectedDb = null;
                return 0;
            }

            DbValidationResult result = selectedDb.validate();
            printValidationIssues(ctx, result);
            if(!result.valid()) {
                ctx.getSource().sendError(Text.translatable("qab.msg.db_select_failed", file, "Invalid database"));
                LOGGER.warn("Failed to select DB '{}': Invalid database", target.toString());
                selectedDb = null;
                return 0;
            }

            ctx.getSource().sendFeedback(Text.translatable("qab.msg.db_selected",
                    target.getFileName().toString(), target.toString()));
            LOGGER.info("Selected DB: {}", selectedDb);
            return 1;
        }
        ctx.getSource().sendError(Text.translatable("qab.msg.db_not_found", file, CS_EXPORT_DIR.toString()));
        return 0;
    }

    // ---- select list ----
    private static int execSelectList(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path candidate = QAB_LIST_DIR.resolve(file + ".json");
        if (Files.exists(candidate)) {
            selectedList = candidate;
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.list_selected",
                    candidate.getFileName().toString(), candidate.toString()));
            LOGGER.info("Selected list: {}", selectedList);
            return 1;
        }
        Path direct = Path.of(file);
        if (Files.exists(direct)) {
            selectedList = direct;
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.list_selected",
                    direct.getFileName().toString(), direct.toString()));
            LOGGER.info("Selected list: {}", selectedList);
            return 1;
        }
        ctx.getSource().sendError(Text.translatable("qab.msg.list_not_found", file, QAB_LIST_DIR.toString()));
        return 0;
    }

    // ---- plan generator ----
    private static int execGeneratePlan(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (selectedDb == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.no_db_selected"));
            return 0;
        }
        if (selectedList == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.no_list_selected"));
            return 0;
        }

        String planName = (name == null || name.isBlank())
                ? "plan-" + LocalDateTime.now().format(PLAN_TIME)
                : name;
        if (!planName.endsWith(".json")) {
            planName += ".json";
        }

        try {
            Files.createDirectories(QAB_PLAN_DIR);

            ShoppingList list = loadShoppingList(selectedList);
            if (list == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.list_parse_failed",
                        selectedList.getFileName().toString(), "JSON parse or read error"));
                return 0;
            }
            if (list.getItems() == null || list.getItems().isEmpty()) {
                ctx.getSource().sendError(Text.translatable("qab.msg.list_empty",
                        selectedList.getFileName().toString()));
                return 0;
            }

            ShopExportData export;
            try {
                export = selectedDb.load();
            } catch (Exception e) {
                ctx.getSource().sendError(Text.translatable("qab.msg.db_load_failed",
                        selectedDb.getPath().getFileName().toString(), e.getMessage()));
                LOGGER.error("Failed to load chunkscanner DB: {}", selectedDb, e);
                return 0;
            }

            ShoppingPlan plan = ShoppingPlanner.generatePlan(list, export);

            Path outPath = QAB_PLAN_DIR.resolve(planName);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(plan);
            try {
                Files.writeString(outPath, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                ctx.getSource().sendError(Text.translatable("qab.msg.plan_write_failed",
                        outPath.getFileName().toString(), e.getMessage()));
                LOGGER.error("Failed to write plan: {}", outPath, e);
                return 0;
            }

            ctx.getSource().sendFeedback(Text.translatable("qab.msg.plan_generated",
                    outPath.getFileName().toString(),
                    plan.getPlan().size(),
                    plan.getTotalCost(),
                    plan.getFailed().size(),
                    plan.getWarn().size()));
            LOGGER.info("Plan generated: {} ({} entries)", outPath, plan.getPlan().size());
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to generate plan", e);
            if (e instanceof IOException) {
                ctx.getSource().sendError(Text.translatable("qab.msg.plan_io_error", e.getMessage()));
            } else {
                ctx.getSource().sendError(Text.translatable("qab.msg.plan_unexpected", e.getMessage()));
            }
            return 0;
        }
    }

    // ---- generate list: 解析原理图生成购物清单 ----
    private static int execGenerateList(CommandContext<FabricClientCommandSource> ctx,
                                        String file, String configStr) {
        if (file == null || file.isBlank()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_no_file"));
            return 0;
        }

        // file 逻辑与其他命令同：先在 schematics/ 下按扩展名查找，否则当全局路径
        Path target = resolveSchematic(file);
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_not_found",
                    file, SCHEMATICS_DIR.toString()));
            return 0;
        }

        ListGenConfig config = ListGenConfig.parse(configStr);
        for (String warning : config.warnings) {
            ctx.getSource().sendError(Text.literal("  [W] " + warning).formatted(Formatting.YELLOW));
        }

        SchematicListGenerator.Result result;
        try {
            result = SchematicListGenerator.generate(target, config);
        } catch (Exception e) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_parse_failed",
                    target.getFileName().toString(), String.valueOf(e.getMessage())));
            LOGGER.error("Failed to parse schematic: {}", target, e);
            return 0;
        }

        ShoppingList list = result.list();
        if (list.getItems().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_empty",
                    target.getFileName().toString()));
            return 0;
        }

        String outName = config.outName != null && !config.outName.isBlank()
                ? config.outName
                : list.getName();
        outName = sanitizeFileName(outName);
        if (!outName.endsWith(".json")) {
            outName += ".json";
        }

        Path outPath = QAB_LIST_DIR.resolve(outName);
        try {
            Files.createDirectories(QAB_LIST_DIR);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(list);
            Files.writeString(outPath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_write_failed",
                    outPath.getFileName().toString(), e.getMessage()));
            LOGGER.error("Failed to write shopping list: {}", outPath, e);
            return 0;
        }

        ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_generated",
                outPath.getFileName().toString(),
                result.blockTypes(),
                result.totalBlocks(),
                result.width() + "x" + result.height() + "x" + result.length()));
        if (result.skipped() > 0) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_skipped", result.skipped())
                    .formatted(Formatting.GRAY));
        }
        String display = config.toDisplayString();
        if (!display.isEmpty()) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_config", display)
                    .formatted(Formatting.GRAY));
        }

        // 生成后自动选中，便于紧接着执行 /qab plan
        selectedList = outPath;
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.list_selected",
                outPath.getFileName().toString(), outPath.toString()).formatted(Formatting.GRAY));

        LOGGER.info("Shopping list generated: {} ({} types, {} blocks)",
                outPath, result.blockTypes(), result.totalBlocks());
        return 1;
    }

    /** 在 schematics/ 下按已知扩展名解析文件名，找不到则回退到全局路径。 */
    private static Path resolveSchematic(String file) {
        if (matchedExtension(file) != null) {
            Path withExt = SCHEMATICS_DIR.resolve(file);
            if (Files.isRegularFile(withExt)) return withExt;
        }
        for (String ext : SCHEMATIC_EXTENSIONS) {
            Path candidate = SCHEMATICS_DIR.resolve(file + ext);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        Path direct = Path.of(file);
        return Files.isRegularFile(direct) ? direct : null;
    }

    /** 去除文件名中的非法字符，避免写入失败或目录穿越。 */
    private static String sanitizeFileName(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.isBlank() || ".".equals(s) || "..".equals(s)) {
            s = "list-" + LocalDateTime.now().format(PLAN_TIME);
        }
        return s;
    }

    // ---- nav apply: 按计划自动寻路 + 到达自动购买 ----
    private static int execNavApply(CommandContext<FabricClientCommandSource> ctx, String file) {
        if (file == null || file.isBlank()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_no_file"));
            return 0;
        }

        // file 逻辑与 select list 同：先找 qab/plan/<file>.json，否则当全局路径
        Path candidate = QAB_PLAN_DIR.resolve(file + ".json");
        Path target = Files.exists(candidate) ? candidate : Path.of(file);
        if (!Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_not_found", file, QAB_PLAN_DIR.toString()));
            return 0;
        }

        ShoppingPlan plan;
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            plan = new Gson().fromJson(json, ShoppingPlan.class);
        } catch (Exception e) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_parse_failed",
                    target.getFileName().toString(), e.getMessage()));
            LOGGER.error("Failed to parse plan: {}", target, e);
            return 0;
        }
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_empty",
                    target.getFileName().toString()));
            return 0;
        }

        QabConfig config = QabConfig.load();
        int queued = QabNavigationHelper.applyPlan(plan, config);
        if (queued <= 0) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_no_target",
                    target.getFileName().toString()));
            return 0;
        }

        ctx.getSource().sendFeedback(Text.translatable("qab.msg.nav_apply_started",
                target.getFileName().toString(), queued, config.getBuyCommand()));
        LOGGER.info("Nav apply started from {}: {} target(s), buy command='{}'",
                target, queued, config.getBuyCommand());
        return 1;
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

    private static void printValidationIssues(CommandContext<FabricClientCommandSource> ctx, DbValidationResult vr) {
        boolean hasIssues = vr != null && (!vr.errors().isEmpty() || !vr.warnings().isEmpty());
        if (hasIssues) {
            int errCount = vr.errors().size();
            int warnCount = vr.warnings().size();
            ctx.getSource().sendError(Text.translatable("qab.msg.db_validation_issues",
                    errCount, warnCount).formatted(errCount > 0? Formatting.RED: Formatting.YELLOW));
            for (String error : vr.errors()) {
                ctx.getSource().sendError(Text.literal("  [E] " + error));
            }
            for (String warning : vr.warnings()) {
                ctx.getSource().sendError(Text.literal("  [W] " + warning).formatted(Formatting.YELLOW));
            }
        }
    }
}
