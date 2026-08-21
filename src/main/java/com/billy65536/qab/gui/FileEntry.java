package com.billy65536.qab.gui;

import java.nio.file.Path;

/**
 * 文件列表行数据。
 *
 * @param path        文件绝对路径（可为全局路径）。
 * @param displayName 展示名（目录内文件为文件名；全局路径文件为其文件名）。
 * @param globalPath  是否为全局路径文件（位于扫描目录之外）；是则以下划线样式展示，悬停 tooltip 展示完整路径。
 */
public record FileEntry(Path path, String displayName, boolean globalPath) {
}
