package com.billy65536.qab.gui;

/**
 * 文件列表行的操作按钮集配置。
 *
 * @param withOpen    行是否显示【打开】按钮（有内页的文件类型；点击行 = 打开内页）。
 * @param withSelect  行是否显示【选择】按钮。
 * @param withSaveBar 列表底部是否挂载保存组件（仅 compound 显示）。
 */
public record ListActions(boolean withOpen, boolean withSelect, boolean withSaveBar) {

    /** db 文件列表：仅【选择】，无内页（点击行退化为选择）。 */
    public static ListActions db() {
        return new ListActions(false, true, false);
    }

    /** compound 文件列表：仅【选择】+ 保存组件。 */
    public static ListActions compound() {
        return new ListActions(false, true, true);
    }

    /** list / plan / region 文件列表：【打开】+【选择】，点击行 = 打开内页。 */
    public static ListActions inner() {
        return new ListActions(true, true, false);
    }
}
