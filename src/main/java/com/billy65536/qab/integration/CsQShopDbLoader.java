package com.billy65536.qab.integration;

import com.billy65536.chunkscanner.api.DatabaseApi;
import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.core.db.DbImage;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.db.DbValidationResult;
import com.billy65536.qab.planner.model.ShopExportData;
import com.billy65536.qab.planner.model.ShopExportEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从 chunkscanner 导出的 ZIP 加载 QShop 数据，并映射为 {@link ShopExportData}。
 *
 * <h3>加载流程</h3>
 * <ol>
 *   <li>构造时 {@link DatabaseApi#openImage(Path)} 解析 ZIP 内的 metadata.json（不校验、不解压）；</li>
 *   <li>调用 {@link #validate()} 校验 metadata 字段合法性与文件 SHA-256 完整性；</li>
 *   <li>调用 {@link #load()}：解压并还原 {@link DbPackage} 实例；</li>
 *   <li>通过 {@link QShopDbAdapter} 读取全部 QShop 记录（自动合并子库增强数据）；</li>
 *   <li>逐条映射为 {@link ShopExportEntry} 并聚合到 {@link ShopExportData}。</li>
 * </ol>
 *
 * <h3>设计原则</h3>
 * 包的打开、校验与加载统一走 chunkscanner 公共 API（{@link DatabaseApi}），
 * 完全不依赖 {@code BinaryChunkDb} 等具体实现类：导出包通过 metadata.json 的
 * {@code databaseType} 字段自动路由到对应的数据库工厂。
 *
 * <p><b>已知的非公共依赖</b>：{@link QShopDbAdapter} 位于 chunkscanner 的
 * {@code components} 包，不属于其公共 API 契约，可能随版本调整。
 * 它是 QShop 数据的专用解码器，目前公共 API 未提供等价能力，
 * 故暂时保留直接依赖；升级 chunkscanner 时需重点回归此处。</p>
 *
 * @see DatabaseApi
 * @see QShopDbAdapter
 * @see ShopExportData
 */
public class CsQShopDbLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab.loader.QShopDbLoader");

    /** 导出 ZIP 文件路径。 */
    private final Path zipPath;
    /** 已解析的导出包镜像（open 后持有 metadata，可复用 validate/load）。 */
    private final DbImage image;

    /**
     * 打开 chunkscanner 导出 ZIP 并解析 metadata。
     * <p>{@link DatabaseApi#openImage(Path)} 仅解析 metadata.json，不校验也不解压。
     *
     * @param zipPath 导出 ZIP 文件路径
     * @throws IOException              文件不存在或 metadata 缺失/可读性错误
     * @throws IllegalArgumentException metadata 结构不合法
     */
    public CsQShopDbLoader(Path zipPath) throws IOException, IllegalArgumentException {
        this.zipPath = zipPath;
        this.image = DatabaseApi.openImage(zipPath);
    }

    /** @return 原始导出 ZIP 文件路径 */
    public Path getPath() { return zipPath; }

    /**
     * 校验导出包合法性。
     * <p>委托 {@link DbImage#validate()} 完成：字段合法性（analyzerId/databaseType 是否注册）
     * + 数据完整性（各文件 SHA-256 是否匹配）。
     *
     * @return 校验结果，{@link DbValidationResult#valid()} 为 {@code true} 时方可安全加载
     */
    public DbValidationResult validate() {
        return this.image.validate();
    }

    /**
     * 加载导出包并将 QShop 记录映射为领域模型。
     * 需执行 {@link CsQShopDbLoader#validate()} 确保数据包合法性。
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>创建临时目录（前缀 {@code qab-chunkscanner-}）；</li>
 *   <li>{@link DbImage#load(Path, boolean) image.load(tmpDir, false)}：
 *       解压 ZIP 并通过 FactoryRegistry 还原 {@link DbPackage}（{@code false} 跳过校验，
 *       因调用方通常已先执行 {@link #validate()}）；</li>
     *   <li>通过 {@link QShopDbAdapter#getAllRecords()} 读取全部 QShop 记录，<b>已自动合并子库
     *       （id=1）增强数据</b>：包括聊天捕获的物品注册 ID（覆盖 itemId）、flags 合并、
     *       detailNbtString 等；</li>
     *   <li>逐条映射为 {@link ShopExportEntry} —— {@code itemName} 为游戏内翻译名，
     *       {@code itemId} 为物品注册 ID（增强后覆盖，可能为空字符串），
     *       {@code detailNbtString} 可为 {@code null}；</li>
     *   <li>{@code finally} 块始终递归删除临时目录，避免磁盘残留。</li>
     * </ol>
     *
     * <h3>异常策略</h3>
     * {@link IOException} 原样上抛；其他异常统一包装为 {@code IOException}（携带 ZIP 路径）。
     *
     * @return 聚合后的 QShop 导出数据
     * @throws IOException 解压失败、数据库加载失败或其他 I/O 错误
     */
    public ShopExportData load() throws IOException {
        Path tmpDir = Files.createTempDirectory("qab-chunkscanner-");
        try {
            DbPackage pkg = image.load(tmpDir, false);

            // 通过 QShopDbAdapter 读取全部记录（自动合并主库与子库增强数据）
            QShopDbAdapter adapter = new QShopDbAdapter(pkg);
            java.util.List<QShopDbAdapter.Record> records = adapter.getAllRecords();

            // 逐条映射为 ShopExportEntry
            ShopExportData data = new ShopExportData();
            for (QShopDbAdapter.Record rec : records) {
                ShopExportEntry entry = new ShopExportEntry();
                entry.setDimId(rec.dimId());
                entry.setX(rec.x());
                entry.setY(rec.y());
                entry.setZ(rec.z());
                entry.setOwner(rec.owner());
                entry.setMode(rec.mode());
                entry.setQuantity(rec.quantity());
                // itemName 为游戏内翻译名，itemId 为物品注册 ID（增强后覆盖）
                entry.setItemName(rec.itemName());
                entry.setPrice(rec.price());
                entry.setItemId(rec.itemId());
                entry.setFlags(rec.flags());
                entry.setTimestamp(rec.timestamp());
                // detailNbtString 来自子库增强数据，可为 null
                entry.setDetailNbtString(rec.detailNbtString());
                data.addEntry(entry);
            }

            try {
                pkg.close();
            } catch (Exception ignored) { }

            LOGGER.info("Loaded {} QShop entries from {}", data.size(), zipPath.getFileName());
            return data;

        } catch (Exception e) {
            throw new IOException("Failed to load chunkscanner data from " + zipPath, e);
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /**
     * 递归删除目录及其所有内容，忽略删除失败。
     */
    private static void deleteRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
        } catch (IOException ignored) {
        }
    }
}
