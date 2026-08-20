package com.billy65536.qab;

import com.billy65536.chunkscanner.api.DatabaseApi;
import com.billy65536.infrastructure.util.archive.ArchiveWriter;
import com.billy65536.infrastructure.util.archive.ValidationResult;
import com.billy65536.qab.compound.CompoundImage;
import com.billy65536.qab.config.BlockMappingConfig;
import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.automatic.ShoppingRunner;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.generator.ListGenConfig;
import com.billy65536.qab.generator.SchematicListGenerator;
import com.billy65536.qab.gui.ShoppingListScreen;
import com.billy65536.qab.gui.ShoppingListSource;
import com.billy65536.qab.integration.CsNavigationHelper;
import com.billy65536.qab.integration.CsQShopDbLoader;
import com.billy65536.qab.planner.ShoppingPlanner;
import com.billy65536.qab.planner.model.ShopExportData;
import com.billy65536.qab.planner.region.Region;
import com.billy65536.qab.planner.region.RegionHighlightRenderer;
import com.billy65536.qab.planner.region.RegionManager;
import com.billy65536.qab.planner.region.RegionSelector;
import com.billy65536.qab.planner.region.RegionTable;
import com.billy65536.qab.planner.model.ShoppingList;
import com.billy65536.qab.planner.model.ShoppingPlan;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * QAB 命令注册（全部为客户端命令）。
 *
 * <pre>
 *   /qab help
 *   /qab gui
 *   /qab db gui|open &lt;file&gt;|list
 *   /qab list gui [file]|open &lt;file&gt;|list|generate &lt;file&gt; [config...]
 *   /qab compound gui|save [name]|open &lt;name&gt;
 *   /qab plan gui|generate [name]|list|open &lt;file&gt;
 *   /qab region gui|open|create|save|selector|highlighter|remove|list
 *   /qab nav apply [file]|pause|resume|stop
 *   /qab nav stash gui|add|remove &lt;index&gt;|list|clear
 * </pre>
 *
 * <p>TODO 除 {@code /qab list gui}（真实打开购物清单界面）外，其余新增的 {@code gui} 子命令均为
 * 占位符，仅提示功能尚未实现。</p>
 *
 * 文件名参数统一用 {@code StringArgumentType.string()}，含会导致 string() 中断的字符（空格及
 * 命令语法保留字符）时，补全项会以双引号包裹，解析时由 {@link CommandPathHelper#resolveFile}
 * 去除引号，确保补全内容选中后可直接被命令完整接收。
 */
public class QabCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/commands");

    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
    /** chunkscanner 导出目录，由其公共 API 提供，避免硬编码路径结构。 */
    private static final Path CS_EXPORT_DIR = DatabaseApi.exportDir();
    private static final Path SCHEMATICS_DIR = GAME_DIR.resolve("schematics");
    private static final Path QAB_DIR = GAME_DIR.resolve("qab");
    private static final Path QAB_LIST_DIR = QAB_DIR.resolve("list");
    private static final Path QAB_PLAN_DIR = QAB_DIR.resolve("plan");
    private static final Path QAB_COMPOUND_DIR = QAB_DIR.resolve("compound");

    /** schematic4j 支持的原理图扩展名。 */
    private static final List<String> SCHEMATIC_EXTENSIONS =
            List.of(".litematic", ".schem", ".schematic", ".nbt");

    private static final DateTimeFormatter PLAN_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    static CsQShopDbLoader selectedDb = null;
    static Path selectedList = null;
    /** 由 {@code /qab plan open} 选中，供 {@code /qab nav apply} 无参数时使用。 */
    static Path selectedPlan = null;

    // ---- auto-complete: 委托 CommandPathHelper，含会导致 string() 中断的字符时自动加引号 ----
    private static final SuggestionProvider<FabricClientCommandSource> DB_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(CS_EXPORT_DIR, ".zip");
    private static final SuggestionProvider<FabricClientCommandSource> LIST_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QAB_LIST_DIR, ".json");
    private static final SuggestionProvider<FabricClientCommandSource> PLAN_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QAB_PLAN_DIR, ".json");
    private static final SuggestionProvider<FabricClientCommandSource> SCHEMATIC_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(SCHEMATICS_DIR,
                    ".litematic", ".schem", ".schematic", ".nbt");
    private static final SuggestionProvider<FabricClientCommandSource> COMPOUND_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QAB_COMPOUND_DIR, ".qcmp");

    /** 分区表 JSON 序列化器（与 RegionManager 一致的 pretty 格式）。 */
    private static final Gson COMPOUND_GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- register ----
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = literal("qab");

            // /qab gui —— 总界面（占位符，尚未实现）
            var gui = literal("gui").executes(QabCommands::execGuiPlaceholder);

            // /qab db gui|open <file>|list —— QShop 数据库（zip）
            var db = literal("db")
                    .then(literal("gui").executes(QabCommands::execGuiPlaceholder))
                    .then(literal("open")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(DB_SUGGESTIONS)
                                    .executes(QabCommands::execDbOpen)))
                    .then(literal("list").executes(QabCommands::execDbList));

            // /qab list gui [file]|open <file>|list|generate <file> [config...] —— 购物清单
            // generate 的 config 格式: key=value [key=value ...]
            var list = literal("list")
                    .then(literal("gui")
                            .executes(QabCommands::execListGui)
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(LIST_SUGGESTIONS)
                                    .executes(ctx -> execListGui(ctx,
                                            StringArgumentType.getString(ctx, "file")))))
                    .then(literal("open")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(LIST_SUGGESTIONS)
                                    .executes(QabCommands::execListOpen)))
                    .then(literal("list").executes(QabCommands::execListList))
                    .then(literal("generate")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(SCHEMATIC_SUGGESTIONS)
                                    .executes(ctx -> execGenerateList(ctx,
                                            StringArgumentType.getString(ctx, "file"), null))
                                    .then(argument("config", StringArgumentType.greedyString())
                                            .executes(ctx -> execGenerateList(ctx,
                                                    StringArgumentType.getString(ctx, "file"),
                                                    StringArgumentType.getString(ctx, "config"))))));

            // /qab compound gui|save [name]|open <name> —— 打包/解包 DB + 分区表
            var compound = literal("compound")
                    .then(literal("gui").executes(QabCommands::execGuiPlaceholder))
                    .then(literal("save")
                            .executes(ctx -> execCompoundSave(ctx, null))
                            .then(argument("name", StringArgumentType.string())
                                    .executes(ctx -> execCompoundSave(ctx,
                                            StringArgumentType.getString(ctx, "name")))))
                    .then(literal("open")
                            .then(argument("name", StringArgumentType.string())
                                    .suggests(COMPOUND_SUGGESTIONS)
                                    .executes(QabCommands::execCompoundOpen)));

            // /qab plan gui|generate [name]|list|open <file> —— 购物计划
            var plan = literal("plan")
                    .then(literal("gui").executes(QabCommands::execGuiPlaceholder))
                    .then(literal("generate")
                            .executes(ctx -> execGeneratePlan(ctx, null))
                            .then(argument("name", StringArgumentType.string())
                                    .executes(ctx -> execGeneratePlan(ctx,
                                            StringArgumentType.getString(ctx, "name")))))
                    .then(literal("list").executes(QabCommands::execPlanList))
                    .then(literal("open")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(PLAN_SUGGESTIONS)
                                    .executes(QabCommands::execPlanOpen)));

            // /qab region gui|open|create|save|selector|highlighter|remove|list —— 区域选择 + TSP 分组
            var region = literal("region")
                    .then(literal("gui").executes(QabCommands::execGuiPlaceholder))
                    .then(literal("open")
                            .then(argument("name", StringArgumentType.string())
                                    .executes(QabCommands::execRegionOpen)))
                    .then(literal("create")
                            .then(argument("name", StringArgumentType.string())
                                    .executes(QabCommands::execRegionCreate)
                                    .then(argument("x1", IntegerArgumentType.integer())
                                            .then(argument("y1", IntegerArgumentType.integer())
                                                    .then(argument("z1", IntegerArgumentType.integer())
                                                            .then(argument("x2", IntegerArgumentType.integer())
                                                                    .then(argument("y2", IntegerArgumentType.integer())
                                                                            .then(argument("z2", IntegerArgumentType.integer())
                                                                                    .executes(QabCommands::execRegionCreateCoords)))))))))
                    .then(literal("save").executes(QabCommands::execRegionSave))
                    .then(literal("selector")
                            .executes(ctx -> execRegionSelector(ctx, null))
                            .then(literal("on").executes(ctx -> execRegionSelector(ctx, true)))
                            .then(literal("off").executes(ctx -> execRegionSelector(ctx, false))))
                    .then(literal("highlighter")
                            .executes(ctx -> execRegionHighlighter(ctx, null))
                            .then(literal("on").executes(ctx -> execRegionHighlighter(ctx, true)))
                            .then(literal("off").executes(ctx -> execRegionHighlighter(ctx, false))))
                    .then(literal("remove")
                            .then(argument("name", StringArgumentType.string())
                                    .executes(QabCommands::execRegionRemove)))
                    .then(literal("list").executes(QabCommands::execRegionList));

            // /qab nav apply [file]|pause|resume|stop —— 自动寻路购买
            var nav = literal("nav")
                    .then(literal("apply")
                            .executes(ctx -> execNavApply(ctx, null))
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(PLAN_SUGGESTIONS)
                                    .executes(ctx -> execNavApply(ctx,
                                            StringArgumentType.getString(ctx, "file")))))
                    .then(literal("stop").executes(QabCommands::execNavStop))
                    .then(literal("pause").executes(QabCommands::execNavPause))
                    .then(literal("resume").executes(QabCommands::execNavResume))
                    // /qab nav stash gui|add|remove <index>|list|clear —— 存货点管理
                    .then(literal("stash")
                            .then(literal("gui").executes(QabCommands::execGuiPlaceholder))
                            .then(literal("add").executes(QabCommands::execStashAdd))
                            .then(literal("list").executes(QabCommands::execStashList))
                            .then(literal("remove")
                                    .then(argument("index", IntegerArgumentType.integer(1))
                                            .executes(ctx -> execStashRemove(ctx,
                                                    IntegerArgumentType.getInteger(ctx, "index")))))
                            .then(literal("clear").executes(QabCommands::execStashClear)));

            var help = literal("help").executes(QabCommands::execHelp);

            root.then(help);
            root.then(gui);
            root.then(db);
            root.then(list);
            root.then(compound);
            root.then(plan);
            root.then(region);
            root.then(nav);

            dispatcher.register(root);
            LOGGER.info("Registered /qab commands");
        });
    }

    // ---- db open ----
    private static int execDbOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path target = CommandPathHelper.resolveFile(CS_EXPORT_DIR, file, ".zip");
        if (target != null && Files.exists(target)) {
            try {
                selectedDb = new CsQShopDbLoader(target);
            } catch (Exception e) {
                if (e instanceof IOException) {
                    ctx.getSource().sendError(Text.translatable("qab.msg.db_open_io_error", e.getMessage()));
                } else if(e instanceof IllegalArgumentException) {
                    ctx.getSource().sendError(Text.translatable("qab.msg.db_open_metadata_invalid", e.getMessage()));
                } else {
                    ctx.getSource().sendError(Text.translatable("qab.msg.db_open_unexpected", e.getMessage()));
                }
                LOGGER.warn("Failed to open DB '{}': {}", target.toString(), e);
                selectedDb = null;
                return 0;
            }

            ValidationResult result = selectedDb.validate();
            printValidationIssues(ctx, result);
            if(!result.valid()) {
                ctx.getSource().sendError(Text.translatable("qab.msg.db_open_failed", file, "Invalid database"));
                LOGGER.warn("Failed to open DB '{}': Invalid database", target.toString());
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

    // ---- list open ----
    private static int execListOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path target = CommandPathHelper.resolveFile(QAB_LIST_DIR, file, ".json");
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_not_found", file, QAB_LIST_DIR.toString()));
            return 0;
        }
        // 打开时校验：JSON 必须可解析且非空，校验通过才选中
        ShoppingList list = loadShoppingList(target);
        if (list == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_open_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return 0;
        }
        if (list.getItems() == null || list.getItems().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_empty",
                    target.getFileName().toString()));
            return 0;
        }
        selectedList = target;
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.list_selected",
                target.getFileName().toString(), target.toString()));
        LOGGER.info("Selected list: {}", selectedList);
        return 1;
    }

    // ---- list gui ----
    private static int execListGui(CommandContext<FabricClientCommandSource> ctx) {
        return execListGui(ctx, null);
    }

    private static int execListGui(CommandContext<FabricClientCommandSource> ctx, String file) {
        Path target;
        if (file != null && !file.isBlank()) {
            target = CommandPathHelper.resolveFile(QAB_LIST_DIR, file, ".json");
            if (target == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.list_not_found", file, QAB_LIST_DIR.toString()));
                return 0;
            }
        } else {
            target = selectedList;
            if (target == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.no_list_selected"));
                return 0;
            }
        }
        if (!Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_not_found",
                    target.getFileName().toString(), QAB_LIST_DIR.toString()));
            return 0;
        }
        ShoppingListSource source = ShoppingListSource.load(target);
        if (source == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_parse_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return 0;
        }
        if (source.size() == 0) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_empty", target.getFileName().toString()));
            return 0;
        }
        LOGGER.info("Opening shopping list GUI: {}", target);
        try {
            var client = ctx.getSource().getClient();
            // 必须用 send（延迟到下一帧）而非同步 setScreen：命令运行于客户端主线程，
            // 同步切屏后，聊天框关闭时的 setScreen(null) 会将其覆盖，导致「有日志但屏幕不出现」。
            // 延迟到聊天框关闭后再切屏，与 infrastructure 的 config GUI 打开方式一致。
            client.send(() -> client.setScreen(new ShoppingListScreen(source, client.currentScreen)));
        } catch (Throwable t) {
            LOGGER.error("Failed to open shopping list GUI for {}", target, t);
            ctx.getSource().sendError(Text.literal("Failed to open list GUI: " + t));
            return 0;
        }
        return 1;
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

            // 按当前区域表做分组 + TSP 排序（未打开区域表时自动创建空表，等价于不做分组）
            ShoppingPlan plan = ShoppingPlanner.generatePlan(list, export, RegionManager.getCurrentTable());

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

    // ---- plan open: 校验并选中购物计划（供 /qab nav apply 无参使用）----
    private static int execPlanOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path target = CommandPathHelper.resolveFile(QAB_PLAN_DIR, file, ".json");
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_not_found",
                    file, QAB_PLAN_DIR.toString()));
            return 0;
        }
        // 打开时校验：JSON 必须可解析且含计划条目，校验通过才选中
        ShoppingPlan plan;
        try {
            String json = Files.readString(target, StandardCharsets.UTF_8);
            plan = new Gson().fromJson(json, ShoppingPlan.class);
        } catch (Exception e) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_failed",
                    target.getFileName().toString(), String.valueOf(e.getMessage())));
            LOGGER.error("Failed to parse plan: {}", target, e);
            return 0;
        }
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_empty",
                    target.getFileName().toString()));
            return 0;
        }
        selectedPlan = target;
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.plan_opened",
                target.getFileName().toString(), target.toString()));
        LOGGER.info("Opened plan: {}", selectedPlan);
        return 1;
    }

    // ---- plan list: 列出已生成的购物计划 ----
    private static int execPlanList(CommandContext<FabricClientCommandSource> ctx) {
        return execFileList(ctx, QAB_PLAN_DIR, ".json",
                "qab.msg.plan_list_empty", "qab.msg.plan_list_header");
    }

    // ---- db list: 列出导出目录中的数据库 ----
    private static int execDbList(CommandContext<FabricClientCommandSource> ctx) {
        return execFileList(ctx, CS_EXPORT_DIR, ".zip",
                "qab.msg.db_list_empty", "qab.msg.db_list_header");
    }

    // ---- list list: 列出已生成的购物清单 ----
    private static int execListList(CommandContext<FabricClientCommandSource> ctx) {
        return execFileList(ctx, QAB_LIST_DIR, ".json",
                "qab.msg.list_list_empty", "qab.msg.list_list_header");
    }

    /** 列出目录内指定扩展名的文件（单层、按文件名排序），输出风格与 stash/region list 一致。 */
    private static int execFileList(CommandContext<FabricClientCommandSource> ctx, Path dir,
                                    String ext, String emptyKey, String headerKey) {
        if (!Files.isDirectory(dir)) {
            ctx.getSource().sendFeedback(Text.translatable(emptyKey).formatted(Formatting.GRAY));
            return 1;
        }
        List<String> names;
        try (var stream = Files.list(dir)) {
            names = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            ctx.getSource().sendError(Text.translatable("qab.msg.dir_list_failed",
                    dir.toString(), e.getMessage()));
            LOGGER.error("Failed to list directory: {}", dir, e);
            return 0;
        }
        if (names.isEmpty()) {
            ctx.getSource().sendFeedback(Text.translatable(emptyKey).formatted(Formatting.GRAY));
            return 1;
        }
        ctx.getSource().sendFeedback(Text.translatable(headerKey, names.size()).formatted(Formatting.AQUA));
        for (int i = 0; i < names.size(); i++) {
            ctx.getSource().sendFeedback(Text.literal("  " + (i + 1) + ". " + names.get(i))
                    .formatted(Formatting.GRAY));
        }
        return 1;
    }

    // ---- generate list: 解析原理图生成购物清单 ----
    private static int execGenerateList(CommandContext<FabricClientCommandSource> ctx,
                                        String file, String configStr) {
        if (file == null || file.isBlank()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_no_file"));
            return 0;
        }

        // 每次执行时实时从 qab:schematic 段物化方块→物品映射（内置默认 + 用户覆盖）
        BlockMappingConfig.reloadFrom(ConfigLoader.getSchematicConfig());
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_mapping_refreshed")
                .formatted(Formatting.GRAY));

        // file 逻辑与其他命令同：先在 schematics/ 下按扩展名查找，否则当全局路径
        Path target = resolveSchematic(file);
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_not_found",
                    file, SCHEMATICS_DIR.toString()));
            return 0;
        }

        ListGenConfig config = ListGenConfig.parse(configStr, ConfigLoader.getSchematicConfig());
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

        String outName = config.outNameOrDefault() != null
                ? config.outNameOrDefault()
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
        // 无法购买的方块（流体、火、活塞头等）单独提示，避免静默丢失
        if (!result.unobtainable().isEmpty()) {
            long unobtainableTotal = result.unobtainable().values().stream().mapToLong(Long::longValue).sum();
            String preview = result.unobtainable().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getKey() + " x" + e.getValue())
                    .collect(Collectors.joining(", "));
            if (result.unobtainable().size() > 5) {
                preview += ", ...";
            }
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_unobtainable",
                    result.unobtainable().size(), unobtainableTotal, preview).formatted(Formatting.YELLOW));
        }
        // 因方块状态规则跳过的方块（门床上半部、流动液体等），说明数量为何少于方块总数
        if (result.stateSkipped() > 0) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_state_skipped",
                    result.stateSkipped()).formatted(Formatting.GRAY));
        }
        // 容器内含物
        if (result.containerItems() > 0) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_container_items",
                    result.containerItems()).formatted(Formatting.GRAY));
        }
        // 库存扣除
        if (result.inventoryUnavailable()) {
            ctx.getSource().sendFeedback(
                    Text.translatable("qab.msg.gen_list_inventory_unavailable").formatted(Formatting.YELLOW));
        } else if (!result.deducted().isEmpty()) {
            long deductedTotal = result.deducted().values().stream().mapToLong(Long::longValue).sum();
            String deductedPreview = result.deducted().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getKey() + " x" + e.getValue())
                    .collect(Collectors.joining(", "));
            if (result.deducted().size() > 5) {
                deductedPreview += ", ...";
            }
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_deducted",
                    result.deducted().size(), deductedTotal, deductedPreview).formatted(Formatting.GRAY));
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
        return CommandPathHelper.resolveFile(SCHEMATICS_DIR, file,
                SCHEMATIC_EXTENSIONS.toArray(new String[0]));
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
        Path target;
        if (file == null || file.isBlank()) {
            // 无参数时使用 /qab plan open 选中的计划，未选中则报错
            if (selectedPlan == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_no_selected_plan"));
                return 0;
            }
            target = selectedPlan;
        } else {
            // file 逻辑与 list open 同：先找 qab/plan/<file>.json，否则当全局路径
            target = CommandPathHelper.resolveFile(QAB_PLAN_DIR, file, ".json");
            if (target == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_not_found",
                        file, QAB_PLAN_DIR.toString()));
                return 0;
            }
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

        // 格式版本校验：版本 1 的计划没有 itemId，无法查堆叠上限做背包容量预判，
        // 而 QShop 在背包不足时会拒绝发货，硬跑只会买了个寂寞。必须重新生成。
        if (!plan.isBuyable()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_outdated",
                    target.getFileName().toString(),
                    plan.getVersion(), ShoppingPlan.FORMAT_VERSION));
            return 0;
        }

        QabConfig config = ConfigLoader.getConfig();
        int queued = CsNavigationHelper.applyPlan(plan, config);
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

    // ---- nav stop: 中止自动购买 ----
    private static int execNavStop(CommandContext<FabricClientCommandSource> ctx) {
        if (!CsNavigationHelper.isActive()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_stop_idle"));
            return 0;
        }
        int remaining = CsNavigationHelper.stop();
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.nav_stop_done", remaining)
                .formatted(Formatting.YELLOW));
        return 1;
    }

    // ---- nav pause/resume: 冻结/恢复自动购买进度 ----
    private static int execNavPause(CommandContext<FabricClientCommandSource> ctx) {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        if (!runner.isRunning()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_pause_idle"));
            return 0;
        }
        if (!runner.pause()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_already_paused"));
            return 0;
        }
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.nav_paused").formatted(Formatting.YELLOW));
        return 1;
    }

    private static int execNavResume(CommandContext<FabricClientCommandSource> ctx) {
        ShoppingRunner runner = ShoppingRunner.getInstance();
        if (!runner.isRunning()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_pause_idle"));
            return 0;
        }
        if (!runner.resume()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.nav_already_resumed"));
            return 0;
        }
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.nav_resumed").formatted(Formatting.GREEN));
        return 1;
    }

    // ---- compound save/open: 利用基础设施归档工具打包/解包 DB + 分区表 ----
    /**
     * 把当前选中的 DB 与当前分区表打包为复合包。
     *
     * <p>复合包为基础设施归档格式（ZIP 注释携带框架元数据）：DB 导出 ZIP 以 STORED 原样内嵌
     * （已是压缩数据，免二次 deflate），分区表以 JSON 文本 entry 写入；业务信息（DB 文件名、
     * 分区表名、区域数）记录在元数据 {@code business} 段，供解包时还原。</p>
     *
     * @param name 复合包名（可空，缺省取 DB 文件名去掉 {@code .zip} 后缀）
     */
    private static int execCompoundSave(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (selectedDb == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.compound_no_db"));
            return 0;
        }
        if (name == null || name.isBlank()) {
            String fn = selectedDb.getPath().getFileName().toString();
            name = fn.endsWith(".zip") ? fn.substring(0, fn.length() - 4) : fn;
        }
        String safe = RegionManager.sanitizeName(name);

        RegionTable table = RegionManager.getCurrentTable();
        String regionName = RegionManager.getCurrentTableName();

        JsonObject business = new JsonObject();
        business.addProperty("databaseFile", selectedDb.getPath().getFileName().toString());
        business.addProperty("regionName", regionName);
        business.addProperty("regionCount", table.size());

        Path out = QAB_COMPOUND_DIR.resolve(safe + ".qcmp");
        boolean overwrite = Files.exists(out);
        try {
            Files.createDirectories(QAB_COMPOUND_DIR);
            try (ArchiveWriter writer = new ArchiveWriter(out)) {
                writer.addStored(CompoundImage.DB_ENTRY, selectedDb.getPath());
                writer.addBytes(CompoundImage.REGIONS_ENTRY,
                        COMPOUND_GSON.toJson(table).getBytes(StandardCharsets.UTF_8));
                // 归档类型 qab:compound 由框架强制写入 business.type
                writer.finish(CompoundImage.ARCHIVE_TYPE, business);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save compound '{}': {}", out, e.getMessage());
            ctx.getSource().sendError(Text.translatable("qab.msg.compound_io_error", e.getMessage()));
            return 0;
        }
        ctx.getSource().sendFeedback(Text.translatable(overwrite
                        ? "qab.msg.compound_saved_overwritten" : "qab.msg.compound_saved",
                safe, out.toAbsolutePath().toString(), regionName, table.size()));
        LOGGER.info("Saved compound {} (db={}, region={}, {} regions)",
                out, selectedDb.getPath(), regionName, table.size());
        return 1;
    }

    /**
     * 解包复合包并供使用：DB 解到 {@code qab/compound/extracted/<名>/} 并设为选中，
     * 分区表写回 region 目录并打开。
     */
    private static int execCompoundOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "name");
        Path target = CommandPathHelper.resolveFile(QAB_COMPOUND_DIR, file, ".qcmp");
        if (target == null || !Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.compound_not_found",
                    file, QAB_COMPOUND_DIR.toString()));
            return 0;
        }
        try (CompoundImage img = CompoundImage.open(target)) {
            ValidationResult result = img.validate();
            printValidationIssues(ctx, result);
            if (!result.valid()) {
                ctx.getSource().sendError(Text.translatable("qab.msg.compound_open_invalid",
                        target.getFileName().toString()));
                return 0;
            }
            // 1) 解出 DB，设为选中
            Path extractDir = QAB_COMPOUND_DIR.resolve("extracted")
                    .resolve(stripExtension(target.getFileName().toString()));
            Files.createDirectories(extractDir);
            Path dbZip = extractDir.resolve(CompoundImage.DB_ENTRY);
            img.copyEntryTo(CompoundImage.DB_ENTRY, dbZip);
            selectedDb = new CsQShopDbLoader(dbZip);
            ValidationResult dbResult = selectedDb.validate();
            if (!dbResult.valid()) {
                printValidationIssues(ctx, dbResult);
                selectedDb = null;
                ctx.getSource().sendError(Text.translatable("qab.msg.compound_db_invalid",
                        img.databaseFileName()));
                return 0;
            }
            // 2) 解出分区表，写入 region 目录并打开
            String regionName = img.regionName();
            Path regionFile = RegionManager.regionDir()
                    .resolve(RegionManager.sanitizeName(regionName) + ".json");
            Files.createDirectories(RegionManager.regionDir());
            img.copyEntryTo(CompoundImage.REGIONS_ENTRY, regionFile);
            RegionManager.open(regionName);

            ctx.getSource().sendFeedback(Text.translatable("qab.msg.compound_opened",
                    target.getFileName().toString(), img.databaseFileName(), regionName,
                    RegionManager.getCurrentTable().size(), target.toAbsolutePath().toString()));
            LOGGER.info("Opened compound {} (db={}, region={})", target, dbZip, regionName);
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to open compound '{}': {}", target, e.getMessage());
            ctx.getSource().sendError(Text.translatable("qab.msg.compound_io_error", e.getMessage()));
            return 0;
        }
    }

    /** 去掉文件名的扩展名（如 {@code a.qcmp -> a}）。 */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ---- gui 占位符：功能尚未实现，统一提示 ----
    private static int execGuiPlaceholder(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.gui_placeholder")
                .formatted(Formatting.GRAY));
        return 1;
    }

    // ---- help: 列出全部子命令与一句话用途 ----
    private static int execHelp(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Text.translatable("qab.help.header").formatted(Formatting.AQUA));
        String[] keys = {
                "qab.help.gui",
                "qab.help.db_open",
                "qab.help.db_list",
                "qab.help.db_gui",
                "qab.help.list_open",
                "qab.help.list_list",
                "qab.help.list_generate",
                "qab.help.list_gui",
                "qab.help.compound_save",
                "qab.help.compound_open",
                "qab.help.compound_gui",
                "qab.help.plan_generate",
                "qab.help.plan_list",
                "qab.help.plan_open",
                "qab.help.plan_gui",
                "qab.help.region_open",
                "qab.help.region_create",
                "qab.help.region_create_coords",
                "qab.help.region_save",
                "qab.help.region_selector",
                "qab.help.region_highlighter",
                "qab.help.region_remove",
                "qab.help.region_list",
                "qab.help.region_gui",
                "qab.help.nav_apply",
                "qab.help.nav_pause",
                "qab.help.nav_resume",
                "qab.help.nav_stop",
                "qab.help.nav_stash_add",
                "qab.help.nav_stash_list",
                "qab.help.nav_stash_remove",
                "qab.help.nav_stash_clear",
                "qab.help.nav_stash_gui",
                "qab.help.help",
        };
        for (String key : keys) {
            ctx.getSource().sendFeedback(Text.translatable(key).formatted(Formatting.GRAY));
        }
        return 1;
    }

    // ---- stash add: 把当前站立位置记为存货点 ----
    private static int execStashAdd(CommandContext<FabricClientCommandSource> ctx) {
        var player = ctx.getSource().getPlayer();
        var world = ctx.getSource().getWorld();
        if (player == null || world == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.stash_no_player"));
            return 0;
        }

        // 记录玩家<b>准星所指</b>的方块；没指向方块则退回脚下坐标。
        // 存货点应当是箱子本身的坐标，而不是玩家站的地方。
        BlockPos pos;
        HitResult hit = player.raycast(6.0, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            pos = blockHit.getBlockPos();
        } else {
            ctx.getSource().sendError(Text.translatable("qab.msg.stash_add_no_target"));
            return 0;
        }

        String dimensionId = world.getRegistryKey().getValue().toString();
        String position = CsNavigationHelper.formatPosition(
                dimensionId, pos.getX(), pos.getY(), pos.getZ());

        QabConfig config = ConfigLoader.getConfig();
        if (!config.addStashPosition(position)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.stash_add_duplicate", position));
            return 0;
        }
        ConfigLoader.saveConfig();

        ctx.getSource().sendFeedback(Text.translatable("qab.msg.stash_add_done",
                position, config.getStashPositions().size()).formatted(Formatting.GREEN));
        return 1;
    }

    // ---- stash list: 列出所有存货点 ----
    private static int execStashList(CommandContext<FabricClientCommandSource> ctx) {
        QabConfig config = ConfigLoader.getConfig();
        List<String> positions = config.getStashPositions();
        if (positions.isEmpty()) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.stash_list_empty")
                    .formatted(Formatting.GRAY));
            return 1;
        }

        ctx.getSource().sendFeedback(Text.translatable("qab.msg.stash_list_header",
                positions.size()).formatted(Formatting.AQUA));
        for (int i = 0; i < positions.size(); i++) {
            ctx.getSource().sendFeedback(Text.literal("  " + (i + 1) + ". " + positions.get(i))
                    .formatted(Formatting.GRAY));
        }
        return 1;
    }

    // ---- stash remove: 按序号移除存货点 ----
    private static int execStashRemove(CommandContext<FabricClientCommandSource> ctx, int index) {
        QabConfig config = ConfigLoader.getConfig();
        // 命令里的序号从 1 开始，与 /qab nav stash list 的显示一致
        String removed = config.removeStashPositionAt(index - 1);
        if (removed == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.stash_remove_bad_index",
                    index, config.getStashPositions().size()));
            return 0;
        }
        ConfigLoader.saveConfig();
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.stash_remove_done",
                removed, config.getStashPositions().size()).formatted(Formatting.YELLOW));
        return 1;
    }

    // ---- stash clear: 清空全部存货点 ----
    private static int execStashClear(CommandContext<FabricClientCommandSource> ctx) {
        QabConfig config = ConfigLoader.getConfig();
        int count = config.clearStashPositions();
        if (count <= 0) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.stash_list_empty")
                    .formatted(Formatting.GRAY));
            return 1;
        }
        ConfigLoader.saveConfig();
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.stash_clear_done", count)
                .formatted(Formatting.YELLOW));
        return 1;
    }

    // ---- region open: 打开（或新建）指定名称的区域表 ----
    private static int execRegionOpen(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean loaded = RegionManager.open(name);
        if (loaded) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_opened",
                    name, RegionManager.getCurrentTable().size()).formatted(Formatting.GREEN));
        } else {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_created_new",
                    name).formatted(Formatting.YELLOW));
        }
        return 1;
    }

    // ---- region create <name>: 设置待填充区域名并开启选择器（左/右键记录两角） ----
    private static int execRegionCreate(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        RegionManager.getCurrentTable(); // 确保已有表（未打开则自动创建）
        RegionSelector.beginRegion(name);
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_create_begin",
                name).formatted(Formatting.GREEN));
        return 1;
    }

    // ---- region create <name> <x1> <y1> <z1> <x2> <y2> <z2>: 直接用坐标创建区域 ----
    private static int execRegionCreateCoords(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        var player = ctx.getSource().getPlayer();
        var world = ctx.getSource().getWorld();
        if (player == null || world == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.stash_no_player"));
            return 0;
        }
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");
        String dim = world.getRegistryKey().getValue().toString();

        Region region = Region.of(x1, y1, z1, x2, y2, z2, dim);
        RegionManager.addRegion(name, region);
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_created",
                name, x1, y1, z1, x2, y2, z2, dim).formatted(Formatting.GREEN));
        return 1;
    }

    // ---- region highlighter [on|off]: 切换区域高亮渲染 ----
    private static int execRegionHighlighter(CommandContext<FabricClientCommandSource> ctx, Boolean on) {
        QabConfig config = ConfigLoader.getConfig();
        boolean next = (on == null) ? !config.isRegionVisible() : on;
        config.setRegionVisible(next);
        ConfigLoader.saveConfig();
        RegionHighlightRenderer.setVisible(next);
        ctx.getSource().sendFeedback(Text.translatable(
                next ? "qab.msg.region_highlighter_on" : "qab.msg.region_highlighter_off")
                .formatted(next ? Formatting.GREEN : Formatting.YELLOW));
        return 1;
    }

    // ---- region save: 持久化当前区域表 ----
    private static int execRegionSave(CommandContext<FabricClientCommandSource> ctx) {
        if (RegionManager.save()) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_saved",
                    RegionManager.getCurrentTableName()).formatted(Formatting.GREEN));
        } else {
            ctx.getSource().sendError(Text.translatable("qab.msg.region_save_failed"));
        }
        return 1;
    }

    // ---- region selector [on|off]: 切换区域选择器 ----
    private static int execRegionSelector(CommandContext<FabricClientCommandSource> ctx, Boolean on) {
        boolean next = (on == null) ? !RegionSelector.isEnabled() : on;
        RegionSelector.setEnabled(next);
        ctx.getSource().sendFeedback(Text.translatable(
                next ? "qab.msg.region_selector_on" : "qab.msg.region_selector_off")
                .formatted(next ? Formatting.GREEN : Formatting.YELLOW));
        return 1;
    }

    // ---- region remove <name>: 删除命名区域 ----
    private static int execRegionRemove(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (RegionManager.removeRegion(name)) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_removed",
                    name).formatted(Formatting.YELLOW));
        } else {
            ctx.getSource().sendError(Text.translatable("qab.msg.region_not_found", name));
        }
        return 1;
    }

    // ---- region list: 列出当前区域表的所有区域 ----
    private static int execRegionList(CommandContext<FabricClientCommandSource> ctx) {
        RegionTable table = RegionManager.getCurrentTable();
        if (table.isEmpty()) {
            ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_list_empty",
                    RegionManager.getCurrentTableName()).formatted(Formatting.GRAY));
            return 1;
        }
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.region_list_header",
                RegionManager.getCurrentTableName(), table.size()).formatted(Formatting.AQUA));
        for (String name : table.names()) {
            Region r = table.get(name);
            if (r == null) continue;
            ctx.getSource().sendFeedback(Text.literal("  " + name + ": " + r.dimension()
                    + "(" + r.minX() + "," + r.minY() + "," + r.minZ() + ")..("
                    + r.maxX() + "," + r.maxY() + "," + r.maxZ() + ")").formatted(Formatting.GRAY));
        }
        return 1;
    }

    public static ShoppingList loadShoppingList(Path path) {
        if (!Files.exists(path)) return null;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return new Gson().fromJson(json, ShoppingList.class);
        } catch (Exception e) {
            LOGGER.error("Failed to load shopping list: {}", path, e);
            return null;
        }
    }

    /** 将购物清单以 Gson pretty 格式写回 JSON（与命令层持久化约定一致）。 */
    public static boolean saveShoppingList(Path path, ShoppingList list) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(list);
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to save shopping list: {}", path, e);
            return false;
        }
    }

    private static void printValidationIssues(CommandContext<FabricClientCommandSource> ctx, ValidationResult vr) {
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
