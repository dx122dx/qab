package com.billy65536.qab.compound;

import com.billy65536.infrastructure.util.archive.ArchiveImage;
import com.billy65536.infrastructure.util.archive.ArchiveMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * QAB 复合包的只读镜像（选中的 DB + 分区表打包后的归档视图）。
 *
 * <p>格式沿用基础设施归档框架：{@code ArchiveWriter} 写出、本类负责读回。包内固定两个负载 entry：</p>
 * <ul>
 *   <li>{@code database.zip} —— 选中 DB 的导出 ZIP（原样 STORED 内嵌，免二次 deflate）；</li>
 *   <li>{@code regions.json} —— 打包时刻分区表的 Gson 序列化。</li>
 * </ul>
 *
 * <p>业务元数据记录在框架元数据段的 {@code business} 里（统一小写驼峰）：
 * {@code databaseFile}（原始 DB 文件名）、{@code regionName}（分区表名）、
 * {@code regionCount}（打包时刻区域数）；归档类型标识为 {@code qab:compound}。</p>
 *
 * <p>读取侧兼容旧版点分 key（{@code database.file} / {@code region.name}）回落，老包仍可打开。</p>
 *
 * <p>打开流程：{@link #open(Path)} 先经 ZIP 注释定位并解析框架元数据，随后调用方
 * {@link #validate()} 做完整性与业务校验，通过后用 {@code copyEntryTo} 将 DB / 分区表
 * 解出落盘供使用。</p>
 */
public final class CompoundImage extends ArchiveImage implements AutoCloseable {

    /** 包内 DB entry 名。 */
    public static final String DB_ENTRY = "database.zip";
    /** 包内分区表 entry 名。 */
    public static final String REGIONS_ENTRY = "regions.json";

    private CompoundImage(Path zipPath, ArchiveMetadata metadata) {
        super(zipPath, metadata);
    }

    /**
     * 打开复合包镜像。
     *
     * @param zipPath 复合包路径
     * @return 镜像（使用方负责 close）
     * @throws IOException 元数据缺失或非法时抛出
     */
    public static CompoundImage open(Path zipPath) throws IOException {
        return new CompoundImage(zipPath, readMetadata(zipPath));
    }

    /** 归档业务类型标识（写入归档元数据 business.type）。 */
    public static final String ARCHIVE_TYPE = "qab:compound";

    /** 复合包必须同时包含 DB 与分区表两个 entry。 */
    @Override
    protected Set<String> requiredEntries() {
        return Set.of(DB_ENTRY, REGIONS_ENTRY);
    }

    @Override
    protected String expectedArchiveType() {
        return ARCHIVE_TYPE;
    }

    /** 业务校验：复合包无必填业务字段；仅当 business 缺 {@code regionName} 时告警。 */
    @Override
    protected void validateBusinessFields(List<String> errors, List<String> warnings) {
        var business = metadata().businessOrEmpty();
        if (!business.has("regionName") && !business.has("region.name")) {
            warnings.add("Missing business 'regionName'; will default to 'default' when opened.");
        }
    }

    /** 解包时的分区表名（business 未记录时回落为 {@code default}）。 */
    public String regionName() {
        var business = metadata().businessOrEmpty();
        if (business.has("regionName")) {
            return business.get("regionName").getAsString();
        }
        return business.has("region.name") ? business.get("region.name").getAsString() : "default";
    }

    /** 打包时 DB 的原始文件名（business 未记录时回落为 entry 名）。 */
    public String databaseFileName() {
        var business = metadata().businessOrEmpty();
        if (business.has("databaseFile")) {
            return business.get("databaseFile").getAsString();
        }
        return business.has("database.file") ? business.get("database.file").getAsString() : DB_ENTRY;
    }

    /**
     * 镜像不持有长期资源（各读取操作按需打开/关闭 ZipFile），实现 {@link AutoCloseable}
     * 仅为支持 try-with-resources 用法。
     */
    @Override
    public void close() {
        // nothing to release
    }
}
