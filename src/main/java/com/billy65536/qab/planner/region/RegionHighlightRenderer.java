package com.billy65536.qab.planner.region;

import com.billy65536.infrastructure.util.render.Box;
import com.billy65536.infrastructure.util.render.BoxRenderer;
import com.billy65536.qab.config.QabConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 区域高亮边框渲染器：把当前区域表中的每个区域转换为 {@link Box} 并委托
 * infrastructure 通用渲染器 {@link BoxRenderer} 绘制线框盒。
 * <p>
 * 每区域颜色由名称哈希派生（区分不同区域）；若选择器已记录第一角，额外绘制一个黄色单点提示框。
 * 可见性由 {@link QabConfig#isRegionVisible()} 控制——本类缓存该值，由命令切换时经
 * {@link #setVisible(boolean)} 更新，避免每帧读取配置文件。
 */
public final class RegionHighlightRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab.region.highlight");

    /** 第一角提示框颜色（黄）。 */
    private static final int PENDING_COLOR = 0xFFFFFF00;

    /** 高亮可见性缓存（避免每帧读盘）。 */
    private static boolean visible = true;

    private RegionHighlightRenderer() {
    }

    /** 注册世界渲染回调，并从配置初始化可见性。 */
    public static void initialize() {
        visible = QabConfig.load().isRegionVisible();
        WorldRenderEvents.LAST.register(RegionHighlightRenderer::doRender);
        LOGGER.info("Region highlight renderer initialized (visible={}).", visible);
    }

    /** 由命令 /qab region visible 更新可见性缓存。 */
    public static void setVisible(boolean v) {
        visible = v;
    }

    private static void doRender(WorldRenderContext context) {
        if (!visible) return;

        RegionTable table = RegionManager.getCurrentTable();
        if (table.isEmpty() && RegionSelector.getPendingCorner() == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;

        List<Box> boxes = new ArrayList<>();

        for (String name : table.names()) {
            Region r = table.get(name);
            if (r == null) continue;
            boxes.add(Box.ofBlocks(r.minX(), r.minY(), r.minZ(),
                    r.maxX(), r.maxY(), r.maxZ(), colorForName(name)));
        }

        // 选择器第一角提示：单点 1×1×1 黄色框
        int[] pending = RegionSelector.getPendingCorner();
        if (pending != null) {
            boxes.add(Box.ofBlocks(pending[0], pending[1], pending[2],
                    pending[0], pending[1], pending[2], PENDING_COLOR));
        }

        BoxRenderer.render(context, boxes);
    }

    /** 由区域名哈希派生稳定的 ARGB 颜色（区分不同区域）。 */
    private static int colorForName(String name) {
        int h = name.hashCode();
        float hue = ((h & 0xFFFF) & 0xFFFF) / (float) 0xFFFF;
        Color c = Color.getHSBColor(hue, 0.7f, 1.0f);
        return 0xFF000000 | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }
}
