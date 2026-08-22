package com.billy65536.qab.gui;

/**
 * 文件列表类型（工具条内容与高亮来源按类型统一）。
 *
 * <p>{@link #LIST}/{@link #PLAN}/{@link #DB}/{@link #REGION}/{@link #COMPOUND}
 * 对应仪表盘导航栏五个可渲染选项卡；{@link #SCHEMATIC} 为隐藏视图
 * （不渲染进导航栏），供「从投影文件生成购物清单」流程使用。</p>
 */
public enum FileListType {
    LIST, PLAN, DB, REGION, COMPOUND, SCHEMATIC
}
