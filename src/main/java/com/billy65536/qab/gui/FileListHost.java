package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.toast.ToastType;
import net.minecraft.text.Text;

/**
 * 文件列表布局宿主抽象：独立屏（{@link FileListScreen}）与仪表盘内嵌
 * （{@link DashboardLayout}）共享同一套 {@link RootLayout} + 工具条，
 * 但「刷新列表 / 打开原理图选择 / 生成后回到清单 / 消息反馈」的行为不同，由宿主各自实现。
 */
public interface FileListHost {

    /** 重扫当前列表并刷新 + 高亮（保存 / 新建 / 生成成功后调用）。 */
    void refreshList();

    /** 打开原理图选择视图（list 工具条「从投影文件生成购物清单」按钮）。 */
    void openSchematicPicker();

    /** 原理图生成清单成功后切回 LIST 视图并刷新高亮（SCHEMATIC 生成完成回调）。 */
    void switchToListAndHighlight();

    /** 消息反馈（宿主内 toast 或聊天框输出）。 */
    void toast(Text text, ToastType type);
}
