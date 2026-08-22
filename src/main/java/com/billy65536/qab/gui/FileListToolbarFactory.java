package com.billy65536.qab.gui;

import com.billy65536.infrastructure.core.gui.layout.AbstractLayout;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import org.jetbrains.annotations.Nullable;

/**
 * 文件列表顶部工具条工厂：按 {@link FileListType} 统一产出工具条布局。
 *
 * <p>所有文件列表（独立屏与仪表盘内嵌）的工具条内容只在此处定义一处，
 * 消除 {@code QabCommands.buildListTopBar} 之类的散落组装（WET 源头）。</p>
 *
 * <p>各类型工具条（全部放上方，高度 {@link RootLayout#TOOLBAR_H}）：</p>
 * <ul>
 *   <li>{@link FileListType#LIST}：左侧「从投影文件生成购物清单」常驻按钮（{@code host.openSchematicPicker()}）
 *       + 右侧「新建购物清单」SaveBar（{@code callbacks.onSave}）；</li>
 *   <li>{@link FileListType#REGION}：右侧「新建区域表」SaveBar（输入表名直接创建空表）；</li>
 *   <li>{@link FileListType#PLAN}：右侧「生成购物计划」SaveBar（基于选中清单生成）；</li>
 *   <li>{@link FileListType#COMPOUND}：右侧「新建包」SaveBar（另存为语义，与底部保存条共用 onSave）；</li>
 *   <li>{@link FileListType#DB}：左侧「刷新列表」常驻按钮（{@code host.refreshList()}）；</li>
 *   <li>{@link FileListType#SCHEMATIC}：无工具条（返回 null）。</li>
 * </ul>
 *
 * <p>新建/生成类按钮全部复用 {@link SaveBarLayout}，业务回调由调用方经
 * {@code callbacks} 传入，工厂只负责布局组装，不承载业务逻辑。</p>
 */
public final class FileListToolbarFactory {

    private FileListToolbarFactory() {
    }

    /**
     * 按类型构建顶部工具条（SCHEMATIC 返回 null，不挂载工具条）。
     *
     * @param tr        字体渲染器
     * @param type      文件列表类型
     * @param callbacks 列表回调（SaveBar 的 onSave / defaultSaveName 直接复用列表回调）
     * @param host      宿主（独立屏跳转 / 仪表盘内嵌切换）
     */
    @Nullable
    public static AbstractLayout build(TextRenderer tr, FileListType type,
                                       FileListView.Callbacks callbacks, FileListHost host) {
        return switch (type) {
            case LIST -> container(tr,
                    button(tr, Text.translatable("qab.msg.list_gui.gen_from_schematic"),
                            host::openSchematicPicker),
                    new SaveBarLayout(tr, callbacks, Text.translatable("qab.msg.list_gui.new_list")));
            case REGION -> container(tr,
                    new SaveBarLayout(tr, callbacks, Text.translatable("qab.msg.region_gui.new_table")));
            case PLAN -> container(tr,
                    new SaveBarLayout(tr, callbacks, Text.translatable("qab.msg.plan_gui.generate")));
            case COMPOUND -> container(tr,
                    new SaveBarLayout(tr, callbacks, Text.translatable("qab.msg.compound_gui.new_package")));
            case DB -> container(tr,
                    button(tr, Text.translatable("qab.msg.db_gui.refresh"), host::refreshList));
            case SCHEMATIC -> null;
        };
    }

    /** 工具条容器：各子布局叠放（按钮在左、SaveBar 右对齐），共用整个工具条区域。 */
    private static AbstractLayout container(TextRenderer tr, AbstractLayout... bars) {
        AbstractLayout topBar = new AbstractLayout() {
            @Override
            protected void renderSelf(DrawContext g, int mx, int my, float delta) {
            }

            @Override
            public void layout() {
                for (AbstractLayout bar : bars) {
                    bar.setBounds(0, 0, this.width, this.height);
                    bar.layout();
                }
            }
        };
        for (AbstractLayout bar : bars) {
            topBar.addChild(bar);
        }
        return topBar;
    }

    /** 常驻按钮（青色文字 + 悬停变亮），点击执行 {@code onClick}。 */
    private static AbstractLayout button(TextRenderer tr, Text label, Runnable onClick) {
        return new AbstractLayout() {
            @Override
            protected void renderSelf(DrawContext g, int mx, int my, float delta) {
                int w = tr.getWidth(label) + 14;
                int[] r = new int[]{10, (this.height - 20) / 2, w, 20};
                boolean hover = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
                g.drawTextWithShadow(tr, label, r[0] + 7, r[1] + (20 - 9) / 2,
                        hover ? 0xFFFFFF55 : 0xFF55FFFF);
            }

            @Override
            protected boolean onMouseClicked(double mx, double my, int button) {
                if (button != 0) {
                    return false;
                }
                int w = tr.getWidth(label) + 14;
                int[] r = new int[]{10, (this.height - 20) / 2, w, 20};
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    onClick.run();
                    return true;
                }
                return false;
            }
        };
    }
}
