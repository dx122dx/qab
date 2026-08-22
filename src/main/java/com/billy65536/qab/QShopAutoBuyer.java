package com.billy65536.qab;

import com.billy65536.chunkscanner.api.DatabaseApi;
import com.billy65536.infrastructure.core.gui.toast.Messenger;
import com.billy65536.infrastructure.core.gui.toast.ToastType;
import com.billy65536.infrastructure.util.archive.ArchiveWriter;
import com.billy65536.infrastructure.util.archive.ValidationResult;
import com.billy65536.qab.compound.CompoundImage;
import com.billy65536.qab.config.BlockMappingConfig;
import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.generator.ListGenConfig;
import com.billy65536.qab.generator.SchematicListGenerator;
import com.billy65536.qab.gui.DashboardLayout;
import com.billy65536.qab.gui.DashboardScreen;
import com.billy65536.qab.gui.FileEntry;
import com.billy65536.qab.gui.FileListView;
import com.billy65536.qab.gui.ListActions;
import com.billy65536.qab.gui.PlanScreen;
import com.billy65536.qab.gui.ShoppingListScreen;
import com.billy65536.qab.gui.ShoppingListSource;
import com.billy65536.qab.integration.CsQShopDbLoader;
import com.billy65536.qab.planner.ShoppingPlanner;
import com.billy65536.qab.planner.model.ShopExportData;
import com.billy65536.qab.planner.model.ShoppingList;
import com.billy65536.qab.planner.model.ShoppingPlan;
import com.billy65536.qab.planner.region.RegionManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * QAB 业务核心：QShop 数据库 / 购物清单 / 购物计划 / 复合包（compound）的选中状态与业务操作。
 *
 * <p>职责边界：本类只承载业务逻辑与选中状态（实例字段），不承载任何命令注册 / 参数解析 /
 * 命令反馈逻辑；命令适配层见 {@link QabCommands}，主模组实例见 {@link QShopAutoBuyMod#BUYER}。</p>
 *
 * <p>实例语义：普通类 + public 构造器（非单例）。QAB 模组自身经
 * {@link QShopAutoBuyMod#BUYER} 使用全局实例；外部模组可 {@code new QShopAutoBuyer()}
 * 创建独立实例（每个实例自带独立选中状态）。共享服务
 * （{@link com.billy65536.qab.automatic.ShoppingRunner}、
 * {@link RegionManager}、{@link com.billy65536.qab.config.ConfigLoader}）仍为全局单例。</p>
 */
public class QShopAutoBuyer {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/buyer");

    // ---- 目录常量（命令层建议器与外部经 QShopAutoBuyer.XXX 引用） ----
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
    /** chunkscanner 导出目录，由其公共 API 提供，避免硬编码路径结构。 */
    public static final Path CS_EXPORT_DIR = DatabaseApi.exportDir();
    public static final Path SCHEMATICS_DIR = GAME_DIR.resolve("schematics");
    public static final Path QAB_DIR = GAME_DIR.resolve("qab");
    public static final Path QAB_LIST_DIR = QAB_DIR.resolve("list");
    public static final Path QAB_PLAN_DIR = QAB_DIR.resolve("plan");
    public static final Path QAB_COMPOUND_DIR = QAB_DIR.resolve("compound");

    /** schematic4j 支持的原理图扩展名。 */
    public static final List<String> SCHEMATIC_EXTENSIONS =
            List.of(".litematic", ".schem", ".schematic", ".nbt");

    /** 默认计划/文件名时间戳格式（命令层生成默认名亦引用）。 */
    public static final DateTimeFormatter PLAN_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    /** 分区表 JSON 序列化器（与 RegionManager 一致的 pretty 格式）。 */
    private static final Gson COMPOUND_GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- 选中状态（实例字段，独立实例各自持有） ----
    /** 当前选中的 DB（{@code /qab db open}、compound open、文件列表【选择】设置）。 */
    private CsQShopDbLoader selectedDb;
    /** 当前选中的购物清单路径（{@code /qab list open}、文件列表【选择】等设置，供计划生成默认使用）。 */
    private Path selectedList;
    /** 由 {@code /qab plan open} 选中，供 {@code /qab nav apply} 无参数时使用。 */
    private Path selectedPlan;

    /** compound 高亮目标（qcmp 文件），由 compound open 或 compound 文件列表【选择】记录；region/db 变更时被清空。 */
    private Path selectedCompound;
    /** 记录 selectedCompound 时的 DB 路径快照（{@code CsQShopDbLoader.getPath()} 为 final，天然不可变）。 */
    private Path selectedCompoundDbPath;
    /** 记录 selectedCompound 时的区域表快照（不可变值对象）。 */
    private RegionManager.RegionSnapshot selectedCompoundRegion;

    /** 外部实例化入口（独立实例自带独立选中状态；共享服务仍为全局单例）。 */
    public QShopAutoBuyer() {
    }

    // ---- 选中状态 getter（GUI 跨包读取用） ----

    /** 当前选中的 DB（未选中返回 null）。 */
    public CsQShopDbLoader getSelectedDb() {
        return selectedDb;
    }

    /** 当前选中的购物清单路径（未选中返回 null）。 */
    public Path getSelectedList() {
        return selectedList;
    }

    /** 当前选中的购物计划路径（未选中返回 null）。 */
    public Path getSelectedPlan() {
        return selectedPlan;
    }

    /** 当前选中的 compound（qcmp）路径（未选中返回 null）。 */
    public Path getSelectedCompound() {
        return selectedCompound;
    }

    // ---- 选中状态 setter（命令层 / 生成流程自动选中用） ----

    /** 设置当前选中的购物清单路径（生成后自动选中等）。 */
    public void setSelectedList(Path target) {
        selectedList = target;
    }

    /** 设置当前选中的购物计划路径（命令层选中后回写）。 */
    public void setSelectedPlan(Path target) {
        selectedPlan = target;
    }

    // ---- db：选择核心 ----

    /**
     * 选择 DB 核心：加载 + 校验 + 设置 {@link #selectedDb}（命令层与文件列表 GUI 双入口复用）。
     *
     * @return 选择结果（ok + 反馈文本 + 校验问题列表），失败时 {@code selectedDb} 保持 null
     */
    public DbSelectResult selectDb(Path target) {
        List<Text> issues = new ArrayList<>();
        try {
            selectedDb = new CsQShopDbLoader(target);
        } catch (Exception e) {
            LOGGER.warn("Failed to open DB '{}': {}", target, e);
            selectedDb = null;
            if (e instanceof IOException) {
                return new DbSelectResult(false, Text.translatable("qab.msg.db_open_io_error", e.getMessage()), issues);
            }
            if (e instanceof IllegalArgumentException) {
                return new DbSelectResult(false, Text.translatable("qab.msg.db_open_metadata_invalid", e.getMessage()), issues);
            }
            return new DbSelectResult(false, Text.translatable("qab.msg.db_open_unexpected", e.getMessage()), issues);
        }
        ValidationResult result = selectedDb.validate();
        issues.addAll(validationIssueTexts(result));
        if (!result.valid()) {
            LOGGER.warn("Failed to open DB '{}': Invalid database", target);
            selectedDb = null;
            return new DbSelectResult(false, Text.translatable("qab.msg.db_open_failed",
                    target.getFileName().toString(), "Invalid database"), issues);
        }
        LOGGER.info("Selected DB: {}", selectedDb);
        return new DbSelectResult(true, Text.translatable("qab.msg.db_selected",
                target.getFileName().toString(), target.toString()), issues);
    }

    /** DB 选择结果（不可变值对象）。 */
    public record DbSelectResult(boolean ok, Text feedback, List<Text> issues) {
    }

    /** 选择 DB（文件列表【选择】/点击行，命令层与仪表盘共用）：加载 + 校验，反馈结果。 */
    public void selectDbFile(Path target) {
        DbSelectResult r = selectDb(target);
        // 校验 issues 属同批消息，统一批量发送（Toast 场景全部入队、不受条数上限挤除）
        Messenger.notifyAll(r.issues(), ToastType.WARN);
        if (r.ok()) {
            Messenger.notify(r.feedback(), ToastType.SUCCESS);
        } else {
            Messenger.error(r.feedback());
        }
    }

    // ---- list / plan：打开内页（命令层与 GUI 共用） ----

    /** 打开清单内页（仪表盘文件列表【打开】/行点击入口，命令层与 GUI 共用）。 */
    public void openListInner(Path target) {
        ShoppingListSource source = ShoppingListSource.load(target);
        if (source == null || source.size() == 0) {
            Messenger.error(Text.translatable("qab.msg.list_parse_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return;
        }
        try {
            var client = MinecraftClient.getInstance();
            client.send(() -> client.setScreen(new ShoppingListScreen(source, client.currentScreen)));
        } catch (Throwable t) {
            LOGGER.error("Failed to open shopping list GUI for {}", target, t);
            Messenger.error(Text.literal("Failed to open list GUI: " + t));
        }
    }

    /** 打开计划内页（仪表盘文件列表【打开】/行点击入口，命令层与 GUI 共用）。 */
    public void openPlanInner(Path target) {
        ShoppingPlan plan = loadShoppingPlan(target);
        if (plan == null || plan.getPlan() == null || plan.getPlan().isEmpty()) {
            Messenger.error(Text.translatable("qab.msg.plan_open_failed",
                    target.getFileName().toString(), "JSON parse or read error"));
            return;
        }
        try {
            var client = MinecraftClient.getInstance();
            client.send(() -> client.setScreen(new PlanScreen(plan,
                    stripExtension(target.getFileName().toString()), client.currentScreen)));
        } catch (Throwable t) {
            LOGGER.error("Failed to open plan GUI for {}", target, t);
            Messenger.error(Text.literal("Failed to open plan GUI: " + t));
        }
    }

    // ---- plan generator ----

    /**
     * 生成并保存购物计划（命令层与 GUI「立即生成」按钮共用）。
     *
     * <p>依赖已选数据库 {@link #selectedDb} 与当前区域表
     * ({@link RegionManager#getCurrentTable()})；不依赖已选清单文件，由调用方传入清单对象。</p>
     *
     * @param list 购物清单（必须非空）
     * @param planName 计划名（不含或含 .json 均可，内部会补全与清洗）
     * @return 生成结果；失败时 {@code errorKey} 为翻译键（如 qab.msg.no_db_selected）
     */
    public GenerateResult generateAndSavePlan(ShoppingList list, String planName) {
        if (selectedDb == null) {
            return new GenerateResult(false, null, null, "qab.msg.no_db_selected", new Object[0]);
        }
        if (list == null || list.getItems() == null || list.getItems().isEmpty()) {
            return new GenerateResult(false, null, null, "qab.msg.list_empty",
                    new Object[]{"(empty)"});
        }

        String planFileName = sanitizeFileName(planName);
        if (!planFileName.endsWith(".json")) {
            planFileName += ".json";
        }

        try {
            Files.createDirectories(QAB_PLAN_DIR);

            ShopExportData export;
            try {
                export = selectedDb.load();
            } catch (Exception e) {
                LOGGER.error("Failed to load chunkscanner DB: {}", selectedDb, e);
                return new GenerateResult(false, null, null, "qab.msg.db_load_failed",
                        new Object[]{selectedDb.getPath().getFileName().toString(), String.valueOf(e.getMessage())});
            }

            // 按当前区域表做分组 + TSP 排序（未打开区域表时自动创建空表，等价于不做分组）
            ShoppingPlan plan = ShoppingPlanner.generatePlan(list, export, RegionManager.getCurrentTable());

            Path outPath = QAB_PLAN_DIR.resolve(planFileName);
            try {
                Files.writeString(outPath, new GsonBuilder().setPrettyPrinting().create().toJson(plan),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("Failed to write plan: {}", outPath, e);
                return new GenerateResult(false, null, null, "qab.msg.plan_write_failed",
                        new Object[]{outPath.getFileName().toString(), String.valueOf(e.getMessage())});
            }

            return new GenerateResult(true, plan, outPath, null, null);
        } catch (Exception e) {
            LOGGER.error("Failed to generate plan", e);
            return new GenerateResult(false, null, null, "qab.msg.plan_unexpected",
                    new Object[]{String.valueOf(e.getMessage())});
        }
    }

    /**
     * 计划生成结果。成功时携带内存中的计划与落盘路径；失败时携带错误翻译键及参数。
     */
    public record GenerateResult(boolean ok, ShoppingPlan plan, Path path,
                                 String errorKey, Object[] errorArgs) {
    }

    /**
     * 原理图生成购物清单的结果（命令层与原理图选择 GUI 双入口复用）。
     * 成功时携带落盘路径与生成结果统计；失败时携带错误翻译键及参数。
     */
    public record GenerateListResult(boolean ok, Path outPath, String errorKey, Object[] errorArgs,
                                     SchematicListGenerator.Result result) {
    }

    // ---- compound save/open: 利用基础设施归档工具打包/解包 DB + 分区表 ----

    /**
     * 保存复合包核心（命令层与文件列表保存组件双入口复用）。
     *
     * @param name 复合包名（可空，缺省取 DB 文件名去掉 {@code .zip} 后缀）
     */
    public CompoundSaveResult saveCompound(String name) {
        if (selectedDb == null) {
            return new CompoundSaveResult(false, Text.translatable("qab.msg.compound_no_db"), null);
        }
        if (name == null || name.isBlank()) {
            String fn = selectedDb.getPath().getFileName().toString();
            name = fn.endsWith(".zip") ? fn.substring(0, fn.length() - 4) : fn;
        }
        String safe = RegionManager.sanitizeName(name);

        var table = RegionManager.getCurrentTable();
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
            return new CompoundSaveResult(false,
                    Text.translatable("qab.msg.compound_io_error", e.getMessage()), null);
        }
        LOGGER.info("Saved compound {} (db={}, region={}, {} regions)",
                out, selectedDb.getPath(), regionName, table.size());
        return new CompoundSaveResult(true, Text.translatable(overwrite
                ? "qab.msg.compound_saved_overwritten" : "qab.msg.compound_saved",
                safe, out.toAbsolutePath().toString(), regionName, table.size()), out);
    }

    /** 复合包保存结果（不可变值对象）。 */
    public record CompoundSaveResult(boolean ok, Text feedback, Path out) {
    }

    /**
     * 解包复合包并供使用核心（命令层与文件列表 GUI 双入口复用）：
     * DB 解到 {@code qab/compound/extracted/<名>/} 并设为选中，分区表写回 region 目录并打开。
     *
     * <p>成功时记录 compound 选择状态（DB 路径与 region 快照均为不可变值），
     * 供 {@link #isCompoundSelectionValid()} 比较是否被后续 region/db 变更所取消。</p>
     */
    public CompoundOpenResult openCompound(Path target) {
        List<Text> issues = new ArrayList<>();
        try (CompoundImage img = CompoundImage.open(target)) {
            ValidationResult result = img.validate();
            issues.addAll(validationIssueTexts(result));
            if (!result.valid()) {
                return new CompoundOpenResult(false, Text.translatable("qab.msg.compound_open_invalid",
                        target.getFileName().toString()), null, null, issues);
            }
            // 1) 解出 DB，设为选中
            Path extractDir = QAB_COMPOUND_DIR.resolve("extracted")
                    .resolve(stripExtension(target.getFileName().toString()));
            Files.createDirectories(extractDir);
            Path dbZip = extractDir.resolve(CompoundImage.DB_ENTRY);
            img.copyEntryTo(CompoundImage.DB_ENTRY, dbZip);
            selectedDb = new CsQShopDbLoader(dbZip);
            ValidationResult dbResult = selectedDb.validate();
            issues.addAll(validationIssueTexts(dbResult));
            if (!dbResult.valid()) {
                selectedDb = null;
                return new CompoundOpenResult(false, Text.translatable("qab.msg.compound_db_invalid",
                        img.databaseFileName()), null, null, issues);
            }
            // 2) 解出分区表，写入 region 目录并打开
            String regionName = img.regionName();
            Path regionFile = RegionManager.regionDir()
                    .resolve(RegionManager.sanitizeName(regionName) + ".json");
            Files.createDirectories(RegionManager.regionDir());
            img.copyEntryTo(CompoundImage.REGIONS_ENTRY, regionFile);
            RegionManager.open(regionName);

            // 记录 compound 选择状态（不可变快照，用于有效性比较）
            selectedCompound = target;
            selectedCompoundDbPath = selectedDb.getPath();
            selectedCompoundRegion = RegionManager.snapshot();
            LOGGER.info("Opened compound {} (db={}, region={})", target, dbZip, regionName);
            return new CompoundOpenResult(true, Text.translatable("qab.msg.compound_opened",
                    target.getFileName().toString(), img.databaseFileName(), regionName,
                    RegionManager.getCurrentTable().size(), target.toAbsolutePath().toString()),
                    dbZip, regionName, issues);
        } catch (Exception e) {
            LOGGER.error("Failed to open compound '{}': {}", target, e.getMessage());
            return new CompoundOpenResult(false,
                    Text.translatable("qab.msg.compound_io_error", e.getMessage()), null, null, issues);
        }
    }

    /** 复合包解包结果（不可变值对象）。 */
    public record CompoundOpenResult(boolean ok, Text feedback, Path dbZip, String regionName, List<Text> issues) {
    }

    /** 选择 qcmp 文件为 compound 高亮目标，并同步记录 db/region 快照（供有效性比较）。命令层与仪表盘共用。 */
    public void selectCompoundFile(FileEntry entry) {
        selectedCompound = entry.path();
        selectedCompoundDbPath = selectedDb != null ? selectedDb.getPath() : null;
        selectedCompoundRegion = RegionManager.snapshot();
        Messenger.notify(Text.translatable("qab.msg.file_gui.selected", entry.displayName()), ToastType.SUCCESS);
    }

    /**
     * compound 选择有效性：比对记录时的 DB 路径与 region 快照（均为不可变值）是否仍与当前一致。
     * 任一变更（打开/新建其他 region、增删区域、切换 DB）则清空 {@link #selectedCompound}（高亮失效）。
     *
     * @return true 表示选择仍有效（高亮保留）
     */
    public boolean isCompoundSelectionValid() {
        boolean valid = selectedCompound != null
                && selectedCompoundDbPath != null
                && selectedCompoundRegion != null
                && selectedDb != null
                && selectedCompoundDbPath.equals(selectedDb.getPath())
                && selectedCompoundRegion.equals(RegionManager.snapshot());
        if (!valid) {
            selectedCompound = null;
        }
        return valid;
    }

    // ---- 仪表盘嵌入文件列表（导航栏选项卡 → 内容区 FileListView） ----

    /** 仪表盘非仪表盘选项卡嵌入文件列表的配置（动作/条目/回调/高亮路径）。 */
    public record DashboardListConfig(ListActions actions, List<FileEntry> entries,
                                      FileListView.Callbacks callbacks, Path highlight) {
    }

    /**
     * 仪表盘选项卡对应的嵌入文件列表配置。回调复用命令层选中逻辑
     * （selectDbFile / 选中清单与计划 / RegionManager.open / selectCompoundFile / saveCompound）。
     * 每次调用重扫目录（支持保存后刷新列表）。
     */
    public DashboardListConfig dashboardListConfig(DashboardLayout.Tab tab) {
        return switch (tab) {
            case DB -> new DashboardListConfig(
                    new ListActions(false, true, false),
                    scanDir(CS_EXPORT_DIR, ".zip", null),
                    new FileListView.Callbacks() {
                        @Override
                        public void onOpen(FileEntry entry) {
                            selectDbFile(entry.path());
                        }

                        @Override
                        public void onSelect(FileEntry entry) {
                            selectDbFile(entry.path());
                        }

                        @Override
                        public void onSave(String name, Consumer<Boolean> done) {
                        }

                        @Override
                        public String defaultSaveName() {
                            return null;
                        }
                    },
                    selectedDb != null ? selectedDb.getPath() : null);
            case LIST -> new DashboardListConfig(
                    new ListActions(true, true, false),
                    scanDir(QAB_LIST_DIR, ".json", selectedList),
                    new FileListView.Callbacks() {
                        @Override
                        public void onOpen(FileEntry entry) {
                            openListInner(entry.path());
                        }

                        @Override
                        public void onSelect(FileEntry entry) {
                            selectedList = entry.path();
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
                    },
                    selectedList);
            case REGION -> new DashboardListConfig(
                    new ListActions(true, true, false),
                    scanDir(RegionManager.regionDir(), ".json", null),
                    new FileListView.Callbacks() {
                        @Override
                        public void onOpen(FileEntry entry) {
                            // region 内页 GUI 暂未实现，统一占位提示
                            Messenger.info(Text.translatable("qab.msg.gui_placeholder").formatted(Formatting.GRAY));
                        }

                        @Override
                        public void onSelect(FileEntry entry) {
                            // region 无独立选中状态，RegionManager 即当前表；选择 = 打开该表
                            String name = stripExtension(entry.path().getFileName().toString());
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
                    },
                    RegionManager.regionDir().resolve(
                            RegionManager.sanitizeName(RegionManager.getCurrentTableName()) + ".json"));
            case COMPOUND -> new DashboardListConfig(
                    new ListActions(false, true, true),
                    scanDir(QAB_COMPOUND_DIR, ".qcmp", null),
                    new FileListView.Callbacks() {
                        @Override
                        public void onOpen(FileEntry entry) {
                            selectCompoundFile(entry);
                        }

                        @Override
                        public void onSelect(FileEntry entry) {
                            selectCompoundFile(entry);
                        }

                        @Override
                        public void onSave(String name, Consumer<Boolean> done) {
                            CompoundSaveResult r = saveCompound(name);
                            if (r.ok()) {
                                Messenger.notify(r.feedback(), ToastType.SUCCESS);
                                done.accept(true);
                                // 保存成功后重扫目录并重建列表（保持滚动与高亮）
                                if (MinecraftClient.getInstance().currentScreen instanceof DashboardScreen ds) {
                                    ds.getDashboardLayout().reloadList();
                                }
                            } else {
                                Messenger.error(r.feedback());
                                done.accept(false);
                            }
                        }

                        @Override
                        public String defaultSaveName() {
                            if (selectedDb == null) {
                                return null;
                            }
                            String fn = selectedDb.getPath().getFileName().toString();
                            return fn.endsWith(".zip") ? fn.substring(0, fn.length() - 4) : fn;
                        }
                    },
                    selectedCompound);
            case PLAN -> new DashboardListConfig(
                    new ListActions(true, true, false),
                    scanDir(QAB_PLAN_DIR, ".json", selectedPlan),
                    new FileListView.Callbacks() {
                        @Override
                        public void onOpen(FileEntry entry) {
                            openPlanInner(entry.path());
                        }

                        @Override
                        public void onSelect(FileEntry entry) {
                            selectedPlan = entry.path();
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
                    },
                    selectedPlan);
            default -> throw new IllegalArgumentException("No list config for tab " + tab);
        };
    }

    // ---- 工具方法（命令层与 GUI 共用） ----

    /**
     * 清洗文件名：非法字符替换为下划线；空名、{@code "."} 或 {@code ".."} 回退为
     * {@code "list-"} + 时间戳名。仅做清洗，不负责补 {@code .json} 扩展名
     * （GUI 另存为 / 立即生成共用）。
     */
    public String sanitizeFileName(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.isBlank() || ".".equals(s) || "..".equals(s)) {
            s = "list-" + LocalDateTime.now().format(PLAN_TIME);
        }
        return s;
    }

    /** 去掉文件名的扩展名（如 {@code a.qcmp -> a}）。 */
    public String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 读取购物清单 JSON；文件不存在或解析失败返回 null。 */
    public ShoppingList loadShoppingList(Path path) {
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
    public boolean saveShoppingList(Path path, ShoppingList list) {
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

    /**
     * 另存为：将清单以新名字写入正式 list 目录，并同步更新清单 name 字段。
     *
     * @param list 购物清单（内存态，调用方持有引用）
     * @param name 新文件名（不含或含 .json 均可，内部清洗补全）
     * @return 落盘后的完整路径；失败返回 null（详情见日志）
     */
    public Path saveShoppingListAs(ShoppingList list, String name) {
        String safeName = sanitizeFileName(name);
        if (!safeName.endsWith(".json")) {
            safeName += ".json";
        }
        Path target = QAB_LIST_DIR.resolve(safeName);
        list.setName(name == null ? "" : name);
        if (!saveShoppingList(target, list)) {
            return null;
        }
        LOGGER.info("Shopping list saved as: {}", target);
        return target;
    }

    /**
     * 从原理图生成购物清单并落盘到 list 目录（命令层与原理图选择 GUI 双入口复用）。
     *
     * <p>流程：实时物化方块→物品映射（{@link BlockMappingConfig}）→ 解析原理图 → 生成清单 →
     * 空清单检查 → 输出名清洗补全 → Gson pretty 写入 {@code QAB_LIST_DIR} → 自动选中新清单。</p>
     *
     * @param schematic 原理图文件路径（调用方需先解析，如 {@code resolveSchematic}）
     * @param config    生成配置（命令层为解析后的配置，GUI 传默认 {@code new ListGenConfig()}）
     * @param outName   输出清单名（可为 null：回落 {@code config.outNameOrDefault()}，再回落清单 name）；
     *                  非 null 时同时写入清单 name 字段
     * @return 生成结果；失败时 {@code errorKey} 为翻译键（如 qab.msg.gen_list_parse_failed）
     */
    public GenerateListResult generateShoppingList(Path schematic, ListGenConfig config, String outName) {
        if (schematic == null) {
            return new GenerateListResult(false, null, "qab.msg.gen_list_no_file", new Object[0], null);
        }
        // 每次执行时实时从 qab:schematic 段物化方块→物品映射（内置默认 + 用户覆盖）
        BlockMappingConfig.reloadFrom(ConfigLoader.getSchematicConfig());

        SchematicListGenerator.Result result;
        try {
            result = SchematicListGenerator.generate(schematic, config);
        } catch (Exception e) {
            LOGGER.error("Failed to parse schematic: {}", schematic, e);
            return new GenerateListResult(false, null, "qab.msg.gen_list_parse_failed",
                    new Object[]{schematic.getFileName().toString(), String.valueOf(e.getMessage())}, null);
        }

        ShoppingList list = result.list();
        if (list.getItems().isEmpty()) {
            return new GenerateListResult(false, null, "qab.msg.gen_list_empty",
                    new Object[]{schematic.getFileName().toString()}, result);
        }

        String name = (outName != null && !outName.isBlank())
                ? outName
                : (config.outNameOrDefault() != null ? config.outNameOrDefault() : list.getName());
        String safeName = sanitizeFileName(name);
        if (!safeName.endsWith(".json")) {
            safeName += ".json";
        }
        if (outName != null && !outName.isBlank()) {
            list.setName(outName);
        }

        Path outPath = QAB_LIST_DIR.resolve(safeName);
        try {
            Files.createDirectories(QAB_LIST_DIR);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(list);
            Files.writeString(outPath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to write shopping list: {}", outPath, e);
            return new GenerateListResult(false, null, "qab.msg.gen_list_write_failed",
                    new Object[]{outPath.getFileName().toString(), e.getMessage()}, result);
        }

        // 生成后自动选中，便于紧接着执行 /qab plan
        setSelectedList(outPath);
        LOGGER.info("Shopping list generated: {} ({} types, {} blocks)",
                outPath, result.blockTypes(), result.totalBlocks());
        return new GenerateListResult(true, outPath, null, null, result);
    }

    /** 读取购物计划 JSON；文件不存在或解析失败返回 null（旧计划缺 name/desc 字段按 null 兼容）。 */
    public ShoppingPlan loadShoppingPlan(Path path) {
        if (!Files.exists(path)) return null;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return new Gson().fromJson(json, ShoppingPlan.class);
        } catch (Exception e) {
            LOGGER.error("Failed to load shopping plan: {}", path, e);
            return null;
        }
    }

    /** 将购物计划以 Gson pretty 格式写回 JSON（与命令层持久化约定一致）。 */
    public boolean saveShoppingPlan(Path path, ShoppingPlan plan) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(plan);
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to save shopping plan: {}", path, e);
            return false;
        }
    }

    /**
     * 扫描目录内指定扩展名的文件；若 {@code extraGlobal} 不在目录内（全局路径文件），
     * 追加为一行（globalPath=true，列表以下划线渲染 + tooltip 完整路径）。
     * 目录不存在/IO 异常时返回空列表（仅 warn，不冒泡）。
     */
    public List<FileEntry> scanDir(Path dir, String ext, Path extraGlobal) {
        return scanDir(dir, List.of(ext), extraGlobal);
    }

    /**
     * 扫描目录内匹配任一扩展名的文件（多扩展名版本，供原理图选择界面使用）。
     * 语义与单扩展名版本一致。
     */
    public List<FileEntry> scanDir(Path dir, List<String> exts, Path extraGlobal) {
        List<FileEntry> entries = new ArrayList<>();
        try {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> Files.isRegularFile(p)
                                    && exts.stream().anyMatch(ext -> p.getFileName().toString().endsWith(ext)))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .forEach(p -> entries.add(new FileEntry(p, p.getFileName().toString(), false)));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list '{}': {}", dir, e.getMessage());
        }
        if (extraGlobal != null && Files.isRegularFile(extraGlobal)) {
            Path abs = extraGlobal.toAbsolutePath().normalize();
            Path absDir = dir.toAbsolutePath().normalize();
            if (!abs.startsWith(absDir)) {
                entries.add(new FileEntry(abs, abs.getFileName().toString(), true));
            }
        }
        return entries;
    }

    /** 把校验结果转成可展示的文本列表（标题 + 逐条 [E]/[W]），供命令与 GUI 双入口复用。 */
    private List<Text> validationIssueTexts(ValidationResult vr) {
        List<Text> texts = new ArrayList<>();
        if (vr == null || (vr.errors().isEmpty() && vr.warnings().isEmpty())) {
            return texts;
        }
        int errCount = vr.errors().size();
        int warnCount = vr.warnings().size();
        texts.add(Text.translatable("qab.msg.db_validation_issues",
                errCount, warnCount).formatted(errCount > 0 ? Formatting.RED : Formatting.YELLOW));
        for (String error : vr.errors()) {
            texts.add(Text.literal("  [E] " + error));
        }
        for (String warning : vr.warnings()) {
            texts.add(Text.literal("  [W] " + warning).formatted(Formatting.YELLOW));
        }
        return texts;
    }
}
