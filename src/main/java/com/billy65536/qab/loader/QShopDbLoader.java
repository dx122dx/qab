package com.billy65536.qab.loader;

import com.billy65536.chunkscanner.components.analyzer.QShopDbAdapter;
import com.billy65536.chunkscanner.core.IChunkDb;
import com.billy65536.qab.planning.model.ShopExportData;
import com.billy65536.qab.planning.model.ShopExportEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 chunkscanner 导出的 ZIP 加载 QShop 数据。
 * <p>
 * 流程：解压 ZIP → 读 metadata.json 获取 {@code databaseType} / {@code databaseName} / {@code scannerId}
 * → 通过 {@link IChunkDb.FactoryRegistry#get(String)} 获取对应工厂 → {@code factory.create(...)} 创建
 * {@link IChunkDb} 实例 → {@link QShopDbAdapter} 读取记录 → 映射为 {@link ShopExportEntry}。
 * <p>
 * 完全不依赖 {@code BinaryChunkDb} 等具体实现类，若 chunkscanner 未来新增数据库格式（如 sqlite），
 * 只需 metadata.json 中 {@code databaseType} 变更即可自动适配。
 */
public class QShopDbLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("qab/QShopDbLoader");
    private static final String METADATA_FILE = "metadata.json";

    private static final String KEY_DATABASE_TYPE = "databaseType";
    private static final String KEY_DATABASE_NAME = "databaseName";
    private static final String KEY_SCANNER_ID = "scannerId";

    /**
     * 从指定 chunkscanner 导出 ZIP 加载 QShop 数据。
     * <p>
     * 内部通过 metadata.json 的 {@code databaseType} 字段，
     * 经 {@link IChunkDb.FactoryRegistry} 获取工厂创建 {@link IChunkDb} 实例。
     *
     * @param zipPath chunkscanner 导出的 ZIP 文件路径
     * @return 加载得到的商店数据
     * @throws IOException 文件读取失败、metadata 缺失、或数据库工厂未注册时抛出
     */
    public static ShopExportData load(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new FileNotFoundException("ZIP file not found: " + zipPath);
        }

        Path tmpDir = Files.createTempDirectory("qab-chunkscanner-");
        try {
            // 1. 解压 ZIP 到临时目录
            unzip(zipPath, tmpDir);

            // 2. 读取 metadata.json
            Path metaPath = tmpDir.resolve(METADATA_FILE);
            if (!Files.exists(metaPath)) {
                throw new IOException("metadata.json not found in ZIP: " + zipPath);
            }

            String metaContent = Files.readString(metaPath, StandardCharsets.UTF_8);
            JsonObject meta = JsonParser.parseString(metaContent).getAsJsonObject();

            String databaseType = meta.get(KEY_DATABASE_TYPE).getAsString();
            String scanId = meta.get(KEY_DATABASE_NAME).getAsString();
            String analyzerId = meta.get(KEY_SCANNER_ID).getAsString();

            LOGGER.info("Loading chunkscanner DB: type={}, scanId={}, analyzerId={}",
                    databaseType, scanId, analyzerId);

            // 3. 通过 FactoryRegistry 获取数据库实例（去耦合！不直接 new BinaryChunkDb）
            IChunkDb.IFactory factory = IChunkDb.FactoryRegistry.get(databaseType);
            if (factory == null) {
                throw new IOException("No factory registered for database type: " + databaseType);
            }

            IChunkDb db = factory.create(scanId, analyzerId, tmpDir);

            // 4. 通过 QShopDbAdapter 读取全部记录（自动合并主库与子库增强数据）
            QShopDbAdapter adapter = new QShopDbAdapter(db);
            java.util.List<QShopDbAdapter.Record> records = adapter.getAllRecords();

            // 5. 逐条映射为 ShopExportEntry（Record 是 Java record，使用访问器方法）
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

        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Failed to load chunkscanner data from " + zipPath, e);
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /**
     * 解压 ZIP 到目标目录，含路径穿越安全校验。
     */
    private static void unzip(Path zipPath, Path targetDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path outPath = targetDir.resolve(entry.getName()).normalize();

                if (!outPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
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
