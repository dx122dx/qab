package com.billy65536.qab.planner.region;

import com.billy65536.qab.config.ConfigLoader;
import com.billy65536.qab.config.QabConfig;
import com.billy65536.qab.integration.CsNavigationHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区域选择器：在 {@code /qab region selector} 开启后，左键记录第一角、右键记录第二角，
 * 两次点击组成一个命名区域并写入当前区域表。
 * <p>
 * 由于 qab 运行环境未引入 {@code fabric-client-events-interaction-v1}（离线构建无法新增该依赖），
 * 这里改为在客户端 tick 中轮询 {@code options.attackKey / useKey} 的上升沿 +
 * {@code crosshairTarget} 取当前准星所指方块，既无新增依赖也不依赖 Mixin。
 * 代价是点击会同时触发原版行为（如破坏方块），但坐标记录不受影响。
 * </p>
 */
public final class RegionSelector {

    private static final Logger LOGGER = LoggerFactory.getLogger("qab.region.selector");

    /** 选择器是否启用（由命令切换并持久化到 QabConfig）。 */
    private static boolean enabled = false;
    /** 待填充区域名（由 /qab region create <name> 设置；为 null 时自动命名）。 */
    private static String pendingName = null;
    /** 第一角所在维度。 */
    private static String corner1Dim = null;
    /** 第一角坐标 {x,y,z}；为 null 表示尚未记录第一角。 */
    private static int[] corner1 = null;
    /** 上一 tick 的按键状态，用于检测上升沿。 */
    private static boolean prevAttack = false;
    private static boolean prevUse = false;
    /** 自动命名计数器。 */
    private static int autoCounter = 0;

    private RegionSelector() {
    }

    /** 注册客户端 tick 回调；启动时从配置恢复选择器开关。 */
    public static void register() {
        enabled = ConfigLoader.getConfig().isRegionSelectorMode();
        ClientTickEvents.END_CLIENT_TICK.register(RegionSelector::onTick);
        LOGGER.info("Region selector registered (enabled={}).", enabled);
    }

    /** 设置选择器开关并持久化到配置；关闭时清空临时状态。 */
    public static void setEnabled(boolean on) {
        enabled = on;
        QabConfig config = ConfigLoader.getConfig();
        config.setRegionSelectorMode(on);
        ConfigLoader.saveConfig();
        if (!on) {
            pendingName = null;
            corner1 = null;
            corner1Dim = null;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 由 {@code /qab region create <name>} 调用：设置待填充区域名并自动开启选择器。
     * 之后左键记第一角、右键记第二角完成该命名区域。
     */
    public static void beginRegion(String name) {
        pendingName = (name == null || name.isBlank()) ? null : name;
        corner1 = null;
        corner1Dim = null;
        setEnabled(true);
    }

    /** 供渲染器绘制"第一角"提示框：返回第一角坐标，未完成返回 null。 */
    public static int[] getPendingCorner() {
        return corner1;
    }

    /** 供渲染器绘制"第一角"提示框：返回第一角维度。 */
    public static String getPendingCornerDim() {
        return corner1Dim;
    }

    private static void onTick(MinecraftClient client) {
        if (!enabled) {
            prevAttack = false;
            prevUse = false;
            return;
        }
        if (client == null || client.player == null || client.world == null) return;

        boolean attack = client.options.attackKey.isPressed();
        boolean use = client.options.useKey.isPressed();
        if (attack && !prevAttack) onLeftClick(client);
        if (use && !prevUse) onRightClick(client);
        prevAttack = attack;
        prevUse = use;
    }

    private static void onLeftClick(MinecraftClient client) {
        BlockPos pos = pointedBlock(client);
        if (pos == null) {
            client.player.sendMessage(Text.translatable("qab.msg.region_no_target").formatted(Formatting.RED), false);
            return;
        }
        String dim = CsNavigationHelper.currentDimension(client);
        corner1 = new int[]{pos.getX(), pos.getY(), pos.getZ()};
        corner1Dim = dim;
        if (pendingName == null) {
            pendingName = "region-" + (++autoCounter);
        }
        client.player.sendMessage(Text.translatable("qab.msg.region_corner_set",
                        pendingName, dim, pos.getX(), pos.getY(), pos.getZ()).formatted(Formatting.GREEN),
                false);
    }

    private static void onRightClick(MinecraftClient client) {
        if (corner1 == null) {
            client.player.sendMessage(Text.translatable("qab.msg.region_no_corner").formatted(Formatting.RED), false);
            return;
        }
        BlockPos pos = pointedBlock(client);
        if (pos == null) {
            client.player.sendMessage(Text.translatable("qab.msg.region_no_target").formatted(Formatting.RED), false);
            return;
        }
        // 以第一角所在维度为准
        Region region = Region.of(corner1[0], corner1[1], corner1[2],
                pos.getX(), pos.getY(), pos.getZ(), corner1Dim);
        String name = pendingName;
        RegionManager.addRegion(name, region);
        client.player.sendMessage(Text.translatable("qab.msg.region_created",
                        name, corner1[0], corner1[1], corner1[2],
                        pos.getX(), pos.getY(), pos.getZ(), corner1Dim).formatted(Formatting.GREEN),
                false);

        // 一次 create 只产出一个区域；清空临时状态准备下一轮
        corner1 = null;
        corner1Dim = null;
        pendingName = null;
    }

    /** 当前准星所指方块坐标；未指向方块返回 null。 */
    private static BlockPos pointedBlock(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getBlockPos();
        }
        return null;
    }
}
