package com.billy65536.qab;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import com.billy65536.infrastructure.core.gui.toast.Messenger;
import com.billy65536.infrastructure.core.gui.toast.ToastType;
import com.billy65536.qab.automatic.ShoppingRunner;
import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.generator.ListGenConfig;
import com.billy65536.qab.generator.SchematicListGenerator;
import com.billy65536.qab.gui.DashboardScreen;
import com.billy65536.qab.gui.FileEntry;
import com.billy65536.qab.gui.FileListScreen;
import com.billy65536.qab.gui.FileListView;
import com.billy65536.qab.gui.ListActions;
import com.billy65536.qab.gui.PlanScreen;
import com.billy65536.qab.gui.ShoppingListScreen;
import com.billy65536.qab.gui.SaveBarLayout;
import com.billy65536.qab.gui.ShoppingListSource;
import com.billy65536.qab.integration.CsNavigationHelper;
import com.billy65536.qab.planner.model.ShoppingList;
import com.billy65536.qab.planner.model.ShoppingPlan;
import com.billy65536.qab.planner.region.Region;
import com.billy65536.qab.planner.region.RegionHighlightRenderer;
import com.billy65536.qab.planner.region.RegionManager;
import com.billy65536.qab.planner.region.RegionSelector;
import com.billy65536.qab.planner.region.RegionTable;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * QAB 命令注册（全部为客户端命令）——命令适配层。
 *
 * <p>本类不承载业务逻辑与选中状态：所有业务操作（数据库/清单/计划/compound 的选中与文件读写）
 * 统一转发到 {@link QShopAutoBuyer} 实例（{@link QShopAutoBuyMod#BUYER}）；命令层仅负责
 * 参数解析、错误反馈与命令树组织。目录常量引用 {@link QShopAutoBuyer} 的公开常量。</p>
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
 * <p>{@code /qab gui} 打开主仪表盘（自动规划 + 自动购物）；db/list/compound/plan/region
 * 的 {@code gui} 子命令均打开文件列表界面；仅 {@code /qab nav stash gui} 仍为占位符
 * （功能尚未实现）。</p>
 *
 * 文件名参数统一用 {@code StringArgumentType.string()}，含会导致 string() 中断的字符（空格及
 * 命令语法保留字符）时，补全项会以双引号包裹，解析时由 {@link CommandPathHelper#resolveFile}
 * 去除引号，确保补全内容选中后可直接被命令完整接收。
 */
public class QabCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/commands");

    // ---- auto-complete: 委托 CommandPathHelper，含会导致 string() 中断的字符时自动加引号 ----
    private static final SuggestionProvider<FabricClientCommandSource> DB_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QShopAutoBuyer.CS_EXPORT_DIR, ".zip");
    private static final SuggestionProvider<FabricClientCommandSource> LIST_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QShopAutoBuyer.QAB_LIST_DIR, ".json");
    private static final SuggestionProvider<FabricClientCommandSource> PLAN_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QShopAutoBuyer.QAB_PLAN_DIR, ".json");
    private static final SuggestionProvider<FabricClientCommandSource> SCHEMATIC_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QShopAutoBuyer.SCHEMATICS_DIR,
                    ".litematic", ".schem", ".schematic", ".nbt");
    private static final SuggestionProvider<FabricClientCommandSource> COMPOUND_SUGGESTIONS =
            CommandPathHelper.suggestBasenames(QShopAutoBuyer.QAB_COMPOUND_DIR, ".qcmp");

    // ---- register ----
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = literal("qab");

            // /qab gui —— 主仪表盘（自动规划 + 自动购物）
            var gui = literal("gui").executes(QabCommands::execGui);

            // /qab db gui|open <file>|list —— QShop 数据库（zip）
            var db = literal("db")
                    .then(literal("gui").executes(QabCommands::execDbGui))
                    .then(literal("open")
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(DB_SUGGESTIONS)
                                    .executes(QabCommands::execDbOpen)))
                    .then(literal("list").executes(QabCommands::execDbList));

            // /qab list gui [file]|open <file>|list|generate <file> [config...] —— 购物清单
            // generate 的 config 格式: key=value [key=value ...]
            var list = literal("list")
                    .then(literal("gui")
                            .executes(QabCommands::execListFileList)
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
                    .then(literal("gui").executes(QabCommands::execCompoundGui))
                    .then(literal("save")
                            .executes(ctx -> execCompoundSave(ctx, null))
                            .then(argument("name", StringArgumentType.string())
                                    .executes(ctx -> execCompoundSave(ctx,
                                            StringArgumentType.getString(ctx, "name")))))
                    .then(literal("open")
                            .then(argument("name", StringArgumentType.string())
                                    .suggests(COMPOUND_SUGGESTIONS)
                                    .executes(QabCommands::execCompoundOpen)));

            // /qab plan gui [file]|generate [name]|list|open <file> —— 购物计划
            var plan = literal("plan")
                    .then(literal("gui")
                            .executes(QabCommands::execPlanFileList)
                            .then(argument("file", StringArgumentType.string())
                                    .suggests(PLAN_SUGGESTIONS)
                                    .executes(ctx -> execPlanGui(ctx,
                                            StringArgumentType.getString(ctx, "file")))))
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
                    .then(literal("gui").executes(QabCommands::execRegionGui))
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
        Path target = CommandPathHelper.resolveFile(QShopAutoBuyer.CS_EXPORT_DIR, file, ".zip");
        if (target == null || !Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.db_not_found",
                    file, QShopAutoBuyer.CS_EXPORT_DIR.toString()));
            return 0;
        }
        QShopAutoBuyer.DbSelectResult r = QShopAutoBuyMod.BUYER.selectDb(target);
        for (Text issue : r.issues()) {
            ctx.getSource().sendError(issue);
        }
        if (!r.ok()) {
            ctx.getSource().sendError(r.feedback());
            return 0;
        }
        ctx.getSource().sendFeedback(r.feedback());
        return 1;
    }

    // ---- list open ----
    private static int execListOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path target = CommandPathHelper.resolveFile(QShopAutoBuyer.QAB_LIST_DIR, file, ".json");
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_not_found",
                    file, QShopAutoBuyer.QAB_LIST_DIR.toString()));
            return 0;
        }
        // 打开时校验：JSON 必须可解析且非空，校验通过才选中
        ShoppingList list = QShopAutoBuyMod.BUYER.loadShoppingList(target);
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
        QShopAutoBuyMod.BUYER.setSelectedList(target);
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.list_selected",
                target.getFileName().toString(), target.toString()));
        LOGGER.info("Selected list: {}", target);
        return 1;
    }

    // ---- list gui ----
    /** 打开购物清单文件列表（{@code /qab list gui} 无参入口）。行【打开】进内页、点击行 = 打开内页。 */
    private static int execListFileList(CommandContext<FabricClientCommandSource> ctx) {
        return openFileListScreen(ctx, QShopAutoBuyer.QAB_LIST_DIR, ".json",
                "qab.msg.file_gui.title_list",
                QShopAutoBuyMod.BUYER.getSelectedList(),
                new ListActions(true, true, false),
                new FileListView.Callbacks() {
                    @Override
                    public void onOpen(FileEntry entry) {
                        openListInner(ctx, entry.path());
                    }

                    @Override
                    public void onSelect(FileEntry entry) {
                        QShopAutoBuyMod.BUYER.setSelectedList(entry.path());
                        Messenger.notify(Text.translatable("qab.msg.file_gui.selected",
                                entry.displayName()), ToastType.SUCCESS);
                    }

                    @Override
                    public void onSave(String name, Consumer<Boolean> done) {
                    }

                    @Override
                    public String defaultSaveName() {
                        return null;
                    }
                }, QShopAutoBuyMod.BUYER.getSelectedList(),
                buildListTopBar(ctx, newListCallbacks()));
    }

    /**
     * 构建 list gui 上方工具条（经 {@link RootLayout} topBar 槽位挂载）：
     * 左侧「从投影文件生成购物清单」常驻按钮 + 右侧「新建购物清单」SaveBar 式输入。
     * 直接组装现有布局组件（SaveBarLayout + 匿名按钮布局），不特化工具条类；
     * 「从投影文件生成购物清单」跳转原理图选择界面（{@link #openSchematicListScreen}），
     * 「新建购物清单」SaveBar 输入创建空清单（{@code newListCallbacks}）。
     */
    private static AbstractLayout buildListTopBar(CommandContext<FabricClientCommandSource> ctx,
                                                  FileListView.Callbacks newListCallbacks) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        Text genLabel = Text.translatable("qab.msg.list_gui.gen_from_schematic");
        AbstractLayout genBtn = new AbstractLayout() {
            @Override
            protected void renderSelf(DrawContext g, int mx, int my, float delta) {
                int w = tr.getWidth(genLabel) + 14;
                int[] r = new int[]{10, (this.height - 20) / 2, w, 20};
                boolean hover = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
                g.drawTextWithShadow(tr, genLabel, r[0] + 7, r[1] + (20 - 9) / 2,
                        hover ? 0xFFFFFF55 : 0xFF55FFFF);
            }

            @Override
            protected boolean onMouseClicked(double mx, double my, int button) {
                if (button != 0) {
                    return false;
                }
                int w = tr.getWidth(genLabel) + 14;
                int[] r = new int[]{10, (this.height - 20) / 2, w, 20};
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    // 打开原理图选择界面（复用 FileListScreen，schematics 多扩展名 + SaveBar 生成保存条）
                    openSchematicListScreen(ctx);
                    return true;
                }
                return false;
            }
        };
        SaveBarLayout newBar = new SaveBarLayout(tr, newListCallbacks,
                Text.translatable("qab.msg.list_gui.new_list"));
        AbstractLayout topBar = new AbstractLayout() {
            @Override
            protected void renderSelf(DrawContext g, int mx, int my, float delta) {
            }

            @Override
            public void layout() {
                genBtn.setBounds(0, 0, this.width, this.height);
                genBtn.layout();
                newBar.setBounds(0, 0, this.width, this.height);
                newBar.layout();
            }
        };
        topBar.addChild(genBtn);
        topBar.addChild(newBar);
        return topBar;
    }

    /**
     * 新建购物清单 SaveBar 式输入的回调：输入名 → 创建 items 为空的 .json（name=输入名）
     * → 刷新当前列表并高亮新文件。
     */
    private static FileListView.Callbacks newListCallbacks() {
        return new FileListView.Callbacks() {
            @Override
            public void onOpen(FileEntry entry) {
            }

            @Override
            public void onSelect(FileEntry entry) {
            }

            @Override
            public void onSave(String name, Consumer<Boolean> done) {
                ShoppingList list = new ShoppingList();
                list.setVersion(1); // 清单格式版本，与 SchematicListGenerator.LIST_VERSION 一致
                list.setName(name);
                Path out = QShopAutoBuyMod.BUYER.saveShoppingListAs(list, name);
                if (out == null) {
                    Messenger.error(Text.translatable("qab.msg.list_gui.save_failed"));
                    done.accept(false);
                    return;
                }
                Messenger.notify(Text.translatable("qab.msg.list_gui.save_success"), ToastType.SUCCESS);
                // 刷新列表并高亮新文件（compound 保存后刷新模式一致）
                if (MinecraftClient.getInstance().currentScreen instanceof FileListScreen fls) {
                    fls.refresh(QShopAutoBuyMod.BUYER.scanDir(QShopAutoBuyer.QAB_LIST_DIR, ".json", null));
                    fls.highlight(out);
                }
                done.accept(true);
            }

            @Override
            public String defaultSaveName() {
                return null;
            }
        };
    }

    /**
     * 打开原理图选择界面（复用 FileListScreen，标题 {@code title_schematic}）：
     * schematics/ 目录按 {@link QShopAutoBuyer#SCHEMATIC_EXTENSIONS} 多扩展名列出；
     * 行点击/选择 = 选中投影文件；SaveBar 保存条默认名 = 投影文件名去扩展名，
     * 输入清单名确认后调生成核心（{@link QShopAutoBuyer#generateShoppingList}），
     * 成功返回 list gui（新清单已自动选中，进入时高亮）。
     */
    private static int openSchematicListScreen(CommandContext<FabricClientCommandSource> ctx) {
        List<FileEntry> entries = QShopAutoBuyMod.BUYER.scanDir(
                QShopAutoBuyer.SCHEMATICS_DIR, QShopAutoBuyer.SCHEMATIC_EXTENSIONS, null);
        // 当前选中的投影（行点击/选择写入；SaveBar 默认名与生成以此为准）
        final Path[] selected = new Path[1];
        var client = ctx.getSource().getClient();
        // 必须用 send（延迟到下一帧）切屏，防止聊天框关闭覆盖
        client.send(() -> client.setScreen(new FileListScreen(
                Text.translatable("qab.msg.file_gui.title_schematic"),
                new ListActions(false, true, true),
                entries,
                new FileListView.Callbacks() {
                    @Override
                    public void onOpen(FileEntry entry) {
                        selectSchematic(entry);
                    }

                    @Override
                    public void onSelect(FileEntry entry) {
                        selectSchematic(entry);
                    }

                    private void selectSchematic(FileEntry entry) {
                        selected[0] = entry.path();
                        Messenger.notify(Text.translatable("qab.msg.file_gui.selected",
                                entry.displayName()), ToastType.SUCCESS);
                    }

                    @Override
                    public void onSave(String name, Consumer<Boolean> done) {
                        Path schematic = selected[0];
                        if (schematic == null) {
                            Messenger.error(Text.translatable("qab.msg.gen_schematic_not_selected"));
                            done.accept(false);
                            return;
                        }
                        // GUI 走默认生成配置；生成核心与命令层共用（避免逻辑双写）
                        QShopAutoBuyer.GenerateListResult r = QShopAutoBuyMod.BUYER.generateShoppingList(
                                schematic, new ListGenConfig(), name);
                        if (!r.ok()) {
                            Messenger.error(Text.translatable(r.errorKey(), r.errorArgs()));
                            done.accept(false);
                            return;
                        }
                        Messenger.notify(Text.translatable("qab.msg.list_gui.save_success"),
                                ToastType.SUCCESS);
                        // 返回 list gui：generateShoppingList 已自动选中新清单，
                        // execListFileList 以 getSelectedList() 为高亮路径 → 刷新并高亮
                        execListFileList(ctx);
                        done.accept(true);
                    }

                    @Override
                    public String defaultSaveName() {
                        Path schematic = selected[0];
                        if (schematic == null) {
                            return null;
                        }
                        return QShopAutoBuyMod.BUYER.stripExtension(
                                schematic.getFileName().toString());
                    }
                },
                -1, null, null)));
        return 1;
    }

    /**
     * 带参打开购物清单内页（{@code /qab list gui <file>}）。
     * 不做其他操作；内页底部【选择】按钮设选中清单并返回文件列表。
     */
    private static int execListGui(CommandContext<FabricClientCommandSource> ctx, String file) {
        Path target = CommandPathHelper.resolveFile(QShopAutoBuyer.QAB_LIST_DIR, file, ".json");
        if (target == null || !Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_not_found",
                    file, QShopAutoBuyer.QAB_LIST_DIR.toString()));
            return 0;
        }
        ShoppingListSource source = ShoppingListSource.load(target);
        if (source == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_parse_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return 0;
        }
        if (source.size() == 0) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_empty",
                    target.getFileName().toString()));
            return 0;
        }
        LOGGER.info("Opening shopping list GUI: {}", target);
        try {
            var client = ctx.getSource().getClient();
            // 必须用 send（延迟到下一帧）而非同步 setScreen：命令运行于客户端主线程，
            // 同步切屏后，聊天框关闭时的 setScreen(null) 会将其覆盖，导致「有日志但屏幕不出现」。
            // 延迟到聊天框关闭后再切屏，与 infrastructure 的 config GUI 打开方式一致。
            client.send(() -> client.setScreen(new ShoppingListScreen(source, client.currentScreen,
                    () -> {
                        QShopAutoBuyMod.BUYER.setSelectedList(target);
                        Messenger.notify(Text.translatable("qab.msg.file_gui.selected",
                                target.getFileName().toString()), ToastType.SUCCESS);
                    })));
        } catch (Throwable t) {
            LOGGER.error("Failed to open shopping list GUI for {}", target, t);
            ctx.getSource().sendError(Text.literal("Failed to open list GUI: " + t));
            return 0;
        }
        return 1;
    }

    /** 打开清单内页（文件列表行点击/【打开】）。返回目标 = 文件列表。 */
    private static void openListInner(CommandContext<FabricClientCommandSource> ctx, Path target) {
        QShopAutoBuyMod.BUYER.openListInner(target);
    }

    // ---- plan gui ----
    /**
     * 打开计划文件列表（{@code /qab plan gui} 无参入口）。
     * 行【打开】进内页查看、【选择】设为选中；点击行 = 打开内页。
     */
    private static int execPlanFileList(CommandContext<FabricClientCommandSource> ctx) {
        return openFileListScreen(ctx, QShopAutoBuyer.QAB_PLAN_DIR, ".json",
                "qab.msg.file_gui.title_plan",
                QShopAutoBuyMod.BUYER.getSelectedPlan(),
                new ListActions(true, true, false),
                new FileListView.Callbacks() {
                    @Override
                    public void onOpen(FileEntry entry) {
                        openPlanInner(ctx, entry.path());
                    }

                    @Override
                    public void onSelect(FileEntry entry) {
                        QShopAutoBuyMod.BUYER.setSelectedPlan(entry.path());
                        Messenger.notify(Text.translatable("qab.msg.file_gui.selected",
                                entry.displayName()), ToastType.SUCCESS);
                    }

                    @Override
                    public void onSave(String name, Consumer<Boolean> done) {
                    }

                    @Override
                    public String defaultSaveName() {
                        return null;
                    }
                }, QShopAutoBuyMod.BUYER.getSelectedPlan(), null);
    }

    /** 带参打开计划内页（{@code /qab plan gui <file>}）。内页底部【选择】按钮设选中计划。 */
    private static int execPlanGui(CommandContext<FabricClientCommandSource> ctx, String file) {
        Path target = CommandPathHelper.resolveFile(QShopAutoBuyer.QAB_PLAN_DIR, file, ".json");
        if (target == null || !Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_not_found",
                    file, QShopAutoBuyer.QAB_PLAN_DIR.toString()));
            return 0;
        }
        // 打开时校验：JSON 必须可解析且含计划条目，校验通过才打开
        ShoppingPlan plan = QShopAutoBuyMod.BUYER.loadShoppingPlan(target);
        if (plan == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return 0;
        }
        if (plan.getPlan() == null || plan.getPlan().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_empty",
                    target.getFileName().toString()));
            return 0;
        }
        String planName = QShopAutoBuyMod.BUYER.stripExtension(target.getFileName().toString());
        LOGGER.info("Opening plan GUI: {}", target);
        try {
            var client = ctx.getSource().getClient();
            // 必须用 send（延迟到下一帧）而非同步 setScreen：命令运行于客户端主线程，
            // 同步切屏后，聊天框关闭时的 setScreen(null) 会将其覆盖，导致「有日志但屏幕不出现」。
            client.send(() -> client.setScreen(new PlanScreen(plan, planName, client.currentScreen,
                    () -> {
                        QShopAutoBuyMod.BUYER.setSelectedPlan(target);
                        Messenger.notify(Text.translatable("qab.msg.file_gui.selected",
                                target.getFileName().toString()), ToastType.SUCCESS);
                    })));
        } catch (Throwable t) {
            LOGGER.error("Failed to open plan GUI for {}", target, t);
            ctx.getSource().sendError(Text.literal("Failed to open plan GUI: " + t));
            return 0;
        }
        return 1;
    }

    /** 打开计划内页（文件列表行点击/【打开】）。返回目标 = 文件列表。 */
    private static void openPlanInner(CommandContext<FabricClientCommandSource> ctx, Path target) {
        QShopAutoBuyMod.BUYER.openPlanInner(target);
    }

    // ---- plan generator ----
    private static int execGeneratePlan(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (QShopAutoBuyMod.BUYER.getSelectedDb() == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.no_db_selected"));
            return 0;
        }
        if (QShopAutoBuyMod.BUYER.getSelectedList() == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.no_list_selected"));
            return 0;
        }

        ShoppingList list = QShopAutoBuyMod.BUYER.loadShoppingList(QShopAutoBuyMod.BUYER.getSelectedList());
        if (list == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_parse_failed",
                    QShopAutoBuyMod.BUYER.getSelectedList().getFileName().toString(), "JSON parse or read error"));
            return 0;
        }
        if (list.getItems() == null || list.getItems().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.list_empty",
                    QShopAutoBuyMod.BUYER.getSelectedList().getFileName().toString()));
            return 0;
        }

        // 命令层与 GUI「立即生成」共用同一生成核心，避免逻辑双写。
        QShopAutoBuyer.GenerateResult result = QShopAutoBuyMod.BUYER.generateAndSavePlan(list,
                (name == null || name.isBlank())
                        ? "plan-" + LocalDateTime.now().format(QShopAutoBuyer.PLAN_TIME)
                        : name);
        if (!result.ok()) {
            ctx.getSource().sendError(Text.translatable(result.errorKey(), result.errorArgs()));
            return 0;
        }

        ShoppingPlan plan = result.plan();
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.plan_generated",
                result.path().getFileName().toString(),
                plan.getPlan().size(),
                plan.getTotalCost(),
                plan.getFailed().size(),
                plan.getWarn().size()));
        LOGGER.info("Plan generated: {} ({} entries)", result.path(), plan.getPlan().size());
        return 1;
    }

    // ---- plan open: 校验并选中购物计划（供 /qab nav apply 无参使用）----
    private static int execPlanOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path target = CommandPathHelper.resolveFile(QShopAutoBuyer.QAB_PLAN_DIR, file, ".json");
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_not_found",
                    file, QShopAutoBuyer.QAB_PLAN_DIR.toString()));
            return 0;
        }
        // 打开时校验：JSON 必须可解析且含计划条目，校验通过才选中
        ShoppingPlan plan = QShopAutoBuyMod.BUYER.loadShoppingPlan(target);
        if (plan == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return 0;
        }
        if (plan.getPlan() == null || plan.getPlan().isEmpty()) {
            ctx.getSource().sendError(Text.translatable("qab.msg.plan_open_empty",
                    target.getFileName().toString()));
            return 0;
        }
        QShopAutoBuyMod.BUYER.setSelectedPlan(target);
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.plan_opened",
                target.getFileName().toString(), target.toString()));
        LOGGER.info("Opened plan: {}", QShopAutoBuyMod.BUYER.getSelectedPlan());
        return 1;
    }

    // ---- plan list: 列出已生成的购物计划 ----
    private static int execPlanList(CommandContext<FabricClientCommandSource> ctx) {
        return execFileList(ctx, QShopAutoBuyer.QAB_PLAN_DIR, ".json",
                "qab.msg.plan_list_empty", "qab.msg.plan_list_header");
    }

    // ---- db list: 列出导出目录中的数据库 ----
    private static int execDbList(CommandContext<FabricClientCommandSource> ctx) {
        return execFileList(ctx, QShopAutoBuyer.CS_EXPORT_DIR, ".zip",
                "qab.msg.db_list_empty", "qab.msg.db_list_header");
    }

    // ---- list list: 列出已生成的购物清单 ----
    private static int execListList(CommandContext<FabricClientCommandSource> ctx) {
        return execFileList(ctx, QShopAutoBuyer.QAB_LIST_DIR, ".json",
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

        // file 逻辑与其他命令同：先在 schematics/ 下按扩展名查找，否则当全局路径
        Path target = resolveSchematic(file);
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("qab.msg.gen_list_not_found",
                    file, QShopAutoBuyer.SCHEMATICS_DIR.toString()));
            return 0;
        }

        ListGenConfig config = ListGenConfig.parse(configStr, ConfigLoader.getSchematicConfig());
        for (String warning : config.warnings) {
            ctx.getSource().sendError(Text.literal("  [W] " + warning).formatted(Formatting.YELLOW));
        }

        // 生成核心统一在 QShopAutoBuyer（命令层与原理图选择 GUI 双入口共用，避免逻辑双写）
        QShopAutoBuyer.GenerateListResult r = QShopAutoBuyMod.BUYER.generateShoppingList(target, config, null);
        if (!r.ok()) {
            ctx.getSource().sendError(Text.translatable(r.errorKey(), r.errorArgs()));
            return 0;
        }
        Path outPath = r.outPath();
        SchematicListGenerator.Result result = r.result();

        ctx.getSource().sendFeedback(Text.translatable("qab.msg.gen_list_mapping_refreshed")
                .formatted(Formatting.GRAY));
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
        ctx.getSource().sendFeedback(Text.translatable("qab.msg.list_selected",
                outPath.getFileName().toString(), outPath.toString()).formatted(Formatting.GRAY));

        LOGGER.info("Shopping list generated: {} ({} types, {} blocks)",
                outPath, result.blockTypes(), result.totalBlocks());
        return 1;
    }

    /** 在 schematics/ 下按已知扩展名解析文件名，找不到则回退到全局路径。 */
    private static Path resolveSchematic(String file) {
        return CommandPathHelper.resolveFile(QShopAutoBuyer.SCHEMATICS_DIR, file,
                QShopAutoBuyer.SCHEMATIC_EXTENSIONS.toArray(new String[0]));
    }

    // ---- nav apply: 按计划自动寻路 + 到达自动购买 ----
    private static int execNavApply(CommandContext<FabricClientCommandSource> ctx, String file) {
        Path target;
        if (file == null || file.isBlank()) {
            // 无参数时使用 /qab plan open 选中的计划，未选中则报错
            if (QShopAutoBuyMod.BUYER.getSelectedPlan() == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_no_selected_plan"));
                return 0;
            }
            target = QShopAutoBuyMod.BUYER.getSelectedPlan();
        } else {
            // file 逻辑与 list open 同：先找 qab/plan/<file>.json，否则当全局路径
            target = CommandPathHelper.resolveFile(QShopAutoBuyer.QAB_PLAN_DIR, file, ".json");
            if (target == null) {
                ctx.getSource().sendError(Text.translatable("qab.msg.nav_apply_not_found",
                        file, QShopAutoBuyer.QAB_PLAN_DIR.toString()));
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

    // ---- compound save: 打包当前 DB + 分区表 ----
    private static int execCompoundSave(CommandContext<FabricClientCommandSource> ctx, String name) {
        QShopAutoBuyer.CompoundSaveResult r = QShopAutoBuyMod.BUYER.saveCompound(name);
        if (!r.ok()) {
            ctx.getSource().sendError(r.feedback());
            return 0;
        }
        ctx.getSource().sendFeedback(r.feedback());
        return 1;
    }

    // ---- compound open: 解包并选用 ----
    private static int execCompoundOpen(CommandContext<FabricClientCommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "name");
        Path target = CommandPathHelper.resolveFile(QShopAutoBuyer.QAB_COMPOUND_DIR, file, ".qcmp");
        if (target == null || !Files.exists(target)) {
            ctx.getSource().sendError(Text.translatable("qab.msg.compound_not_found",
                    file, QShopAutoBuyer.QAB_COMPOUND_DIR.toString()));
            return 0;
        }
        QShopAutoBuyer.CompoundOpenResult r = QShopAutoBuyMod.BUYER.openCompound(target);
        for (Text issue : r.issues()) {
            ctx.getSource().sendError(issue);
        }
        if (!r.ok()) {
            ctx.getSource().sendError(r.feedback());
            return 0;
        }
        ctx.getSource().sendFeedback(r.feedback());
        return 1;
    }

    // ---- 通用文件列表：db / list / plan / compound / region 五类文件列表工厂 ----

    /**
     * 统一文件列表工厂（db / list / plan / region / compound 共用）。
     *
     * @param titleKey 标题翻译键（按类型定制：title_db/title_list/title_plan/title_region/title_compound）
     */
    private static int openFileListScreen(CommandContext<FabricClientCommandSource> ctx, Path dir, String ext,
                                          String titleKey, Path extraGlobal, ListActions actions,
                                          FileListView.Callbacks callbacks, Path highlightPath,
                                          @Nullable AbstractLayout topBar) {
        List<FileEntry> entries = QShopAutoBuyMod.BUYER.scanDir(dir, ext, extraGlobal);
        int highlightedRow = findHighlightedRow(entries, highlightPath);
        var client = ctx.getSource().getClient();
        // 必须用 send（延迟到下一帧）切屏，防止聊天框关闭覆盖
        client.send(() -> client.setScreen(new FileListScreen(
                Text.translatable(titleKey), actions, entries, callbacks, highlightedRow,
                topBar, null)));
        return 1;
    }

    /** 在扫描结果中按归一化绝对路径查找高亮行（未命中返回 -1）。 */
    private static int findHighlightedRow(List<FileEntry> entries, Path path) {
        if (path == null) {
            return -1;
        }
        Path norm = path.toAbsolutePath().normalize();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path().toAbsolutePath().normalize().equals(norm)) {
                return i;
            }
        }
        return -1;
    }

    /** db 文件列表（行仅【选择】，点击行 = 选择）。 */
    private static int execDbGui(CommandContext<FabricClientCommandSource> ctx) {
        return openFileListScreen(ctx, QShopAutoBuyer.CS_EXPORT_DIR, ".zip",
                "qab.msg.file_gui.title_db", null,
                new ListActions(false, true, false),
                new FileListView.Callbacks() {
                    @Override
                    public void onOpen(FileEntry entry) {
                        QShopAutoBuyMod.BUYER.selectDbFile(entry.path());
                    }

                    @Override
                    public void onSelect(FileEntry entry) {
                        QShopAutoBuyMod.BUYER.selectDbFile(entry.path());
                    }

                    @Override
                    public void onSave(String name, Consumer<Boolean> done) {
                    }

                    @Override
                    public String defaultSaveName() {
                        return null;
                    }
                }, QShopAutoBuyMod.BUYER.getSelectedDb() != null
                        ? QShopAutoBuyMod.BUYER.getSelectedDb().getPath() : null, null);
    }

    /** region 文件列表（行【打开】+【选择】，点击行 = 占位内页；选择 = 打开该表）。 */
    private static int execRegionGui(CommandContext<FabricClientCommandSource> ctx) {
        return openFileListScreen(ctx, RegionManager.regionDir(), ".json",
                "qab.msg.file_gui.title_region", null,
                new ListActions(true, true, false),
                new FileListView.Callbacks() {
                    @Override
                    public void onOpen(FileEntry entry) {
                        // region 内页 GUI 暂未实现，统一占位提示
                        Messenger.info(Text.translatable("qab.msg.gui_placeholder").formatted(Formatting.GRAY));
                    }

                    @Override
                    public void onSelect(FileEntry entry) {
                        // region 无独立选中状态，RegionManager 即当前表；选择 = 打开该表
                        String name = QShopAutoBuyMod.BUYER.stripExtension(entry.path().getFileName().toString());
                        RegionManager.open(name);
                        Messenger.notify(Text.translatable("qab.msg.region_opened",
                                name, RegionManager.getCurrentTable().size()), ToastType.SUCCESS);
                    }

                    @Override
                    public void onSave(String name, Consumer<Boolean> done) {
                    }

                    @Override
                    public String defaultSaveName() {
                        return null;
                    }
                }, RegionManager.regionDir().resolve(
                        RegionManager.sanitizeName(RegionManager.getCurrentTableName()) + ".json"),
                null);
    }

    /** compound 文件列表（行仅【选择】+ 保存组件，高亮选中的 qcmp；点击行 = 选择）。 */
    private static int execCompoundGui(CommandContext<FabricClientCommandSource> ctx) {
        // region/db 变更则取消 compound 选择（高亮失效）
        QShopAutoBuyMod.BUYER.isCompoundSelectionValid();
        return openFileListScreen(ctx, QShopAutoBuyer.QAB_COMPOUND_DIR, ".qcmp",
                "qab.msg.file_gui.title_compound", null,
                new ListActions(false, true, true),
                new FileListView.Callbacks() {
                    @Override
                    public void onOpen(FileEntry entry) {
                        QShopAutoBuyMod.BUYER.selectCompoundFile(entry);
                        refreshCompoundHighlight(entry);
                    }

                    @Override
                    public void onSelect(FileEntry entry) {
                        QShopAutoBuyMod.BUYER.selectCompoundFile(entry);
                        refreshCompoundHighlight(entry);
                    }

                    @Override
                    public void onSave(String name, Consumer<Boolean> done) {
                        QShopAutoBuyer.CompoundSaveResult r = QShopAutoBuyMod.BUYER.saveCompound(name);
                        if (r.ok()) {
                            Messenger.notify(r.feedback(), ToastType.SUCCESS);
                            done.accept(true);
                            // 保存成功后刷新列表（保持滚动与高亮）
                            if (ctx.getSource().getClient().currentScreen instanceof FileListScreen fls) {
                                fls.refresh(QShopAutoBuyMod.BUYER.scanDir(
                                        QShopAutoBuyer.QAB_COMPOUND_DIR, ".qcmp", null));
                            }
                        } else {
                            Messenger.error(r.feedback());
                            done.accept(false);
                        }
                    }

                    @Override
                    public String defaultSaveName() {
                        if (QShopAutoBuyMod.BUYER.getSelectedDb() == null) {
                            return null;
                        }
                        String fn = QShopAutoBuyMod.BUYER.getSelectedDb().getPath().getFileName().toString();
                        return fn.endsWith(".zip") ? fn.substring(0, fn.length() - 4) : fn;
                    }
                }, QShopAutoBuyMod.BUYER.getSelectedCompound(), null);
    }

    /** 选择 compound 后即时刷新已打开列表的高亮行（列表屏幕仍打开时）。 */
    private static void refreshCompoundHighlight(FileEntry entry) {
        if (entry == null) {
            return;
        }
        if (MinecraftClient.getInstance().currentScreen instanceof FileListScreen fls) {
            fls.highlight(entry.path());
        }
    }

    // ---- gui: 打开主仪表盘（自动规划 + 自动购物） ----
    private static int execGui(CommandContext<FabricClientCommandSource> ctx) {
        var client = ctx.getSource().getClient();
        // 必须用 send（延迟到下一帧）切屏：命令运行于客户端主线程，同步切屏后，
        // 聊天框关闭时的 setScreen(null) 会将其覆盖（与 db/list 文件列表打开方式一致）。
        client.send(() -> client.setScreen(new DashboardScreen()));
        LOGGER.info("Opened QAB dashboard GUI");
        return 1;
    }

    // ---- gui placeholder: 尚未实现的内页统一占位 ----
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
}
