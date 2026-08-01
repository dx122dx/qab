package com.billy65536.qab.loader;

import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.chunkscanner.core.db.DbPackage;
import com.billy65536.chunkscanner.core.db.DbValidationResult;
import com.billy65536.qab.planning.model.ShopExportData;
import com.billy65536.qab.planning.model.ShopExportEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从 chunkscanner 导出的 ZIP 加载 QShop 数据。
 * <p>
 * 流程：{@link DbPackage#open(Path)} 解析 metadata → {@link DbPackage#validate()} 校验完整性
 * → {@link DbPackage#load(Path, boolean)} 解压并还原 {@link IChunkDb} 实例
 * → {@link QShopDbAdapter} 读取记录 → 映射为 {@link ShopExportEntry}。
 * <p>
 * 完全不依赖 {@code BinaryChunkDb} 等具体实现类，DbPackage 通过 metadata.json 的
 * {@code databaseType} 字段自动路由到对应的 {@link IChunkDb.FactoryRegistry} 工厂。
 */
public class QShopDbLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/QShopDbLoader");

    /**
     * 从指定 chunkscanner 导出 ZIP 加载 QShop 数据。
     * <p>
     * 使用 {@link DbPackage} API 替代手动解压和 metadata 解析，
     * 与 chunkscanner 保持结构对齐。
     *
     * @param zipPath chunkscanner 导出的 ZIP 文件路径
     * @return 加载得到的商店数据
     * @throws IOException 文件读取失败、metadata 缺失、校验未通过、或数据库工厂未注册时抛出
     */
    public static ShopExportData load(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new FileNotFoundException("ZIP file not found: " + zipPath);
        }

        // 1. 打开导出包并解析 metadata（不先解压）
        DbPackage pkg = DbPackage.open(zipPath);
        DbPackage.Meta meta = pkg.meta();

        LOGGER.info("Opened chunkscanner package: type={}, scanId={}, analyzerId={}",
                meta.databaseType(), meta.databaseName(), meta.analyzerId());

        // 2. 校验完整性（字段合法性 + SHA256）
        DbValidationResult validation = pkg.validate();
        if (!validation.valid()) {
            throw new IOException("Package validation failed: " + validation.errors());
        }
        if (!validation.warnings().isEmpty()) {
            LOGGER.warn("Package validation warnings: {}", validation.warnings());
        }

        // 3. 解压到临时目录并通过 factories 还原 IChunkDb（跳过重复校验）
        Path tmpDir = Files.createTempDirectory("qab-chunkscanner-");
        try {
            IChunkDb db = pkg.load(tmpDir, false);

            // 4. 通过 QShopDbAdapter 读取全部记录（自动合并主库与子库增强数据）
            QShopDbAdapter adapter = new QShopDbAdapter(db);
            java.util.List<QShopDbAdapter.Record> records = adapter.getAllRecords();

            // 5. 逐条映射为 ShopExportEntry
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
                entry.setItemName(rec.itemName());
                entry.setPrice(rec.price());
                entry.setItemId(rec.itemId());
                entry.setFlags(rec.flags());
                entry.setTimestamp(rec.timestamp());
                entry.setDetailNbtString(rec.detailNbtString());
                data.addEntry(entry);
            }

            LOGGER.info("Loaded {} QShop entries from {}", data.size(), zipPath.getFileName());
            return data;

        } catch (IOException ioe) {
            throw ioe;
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
