package com.billy65536.qab.planning.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从 chunkscanner ZIP 导出加载的 QShop 数据容器。
 * 由 {@link com.billy65536.qab.loader.QShopDbLoader} 填充。
 */
public class ShopExportData {
    private final List<ShopExportEntry> entries;

    public ShopExportData() {
        this.entries = new ArrayList<>();
    }

    public ShopExportData(List<ShopExportEntry> entries) {
        this.entries = new ArrayList<>(entries);
    }

    public void addEntry(ShopExportEntry entry) {
        this.entries.add(entry);
    }

    public List<ShopExportEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
