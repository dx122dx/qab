package com.billy65536.qab.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * QAB 常规运行时配置（AutoConfig 驱动，JSON：{gameDir}/config/qab_config.json）。
 *
 * <p>仅包含 QAB 自身需要的可配置项，不依赖 chunkscanner 的配置类。
 * 持久化与 GUI 由 AutoConfig 的 {@code GsonConfigSerializer} 接管，
 * 经 infrastructure 的 {@code /inf config get|set|reset|gui qab:config/...} 读写。</p>
 *
 * <p><b>注意：本类内严禁声明任何 static 字段（包括 static final 常量与 Logger）。</b>
 * AutoConfig 的 GUI 反射收集配置字段时会把 static 字段纳入索引，导致保存时字段错位、
 * 尝试写入 {@code static final} 字段而抛 {@code IllegalAccessException}（表现为
 * 「配置编辑窗口输入数据后保存崩溃」）。因此：</p>
 * <ul>
 *   <li>默认值<b>直接以内联字面量写在实例字段初始化器</b>里（实例字段本就是配置项，安全）；</li>
 *   <li>上下限等校验常量<b>内联于 {@link #validatePostLoad()}</b> 并配注释，不能做成实例字段
 *       （否则会被当成配置项显示在 GUI / 写入 JSON），也不能做成 static 字段。</li>
 * </ul>
 *
 * <h3>购买相关</h3>
 * <ul>
 *   <li>{@code buyDelayMs}：到达告示牌后、发送购买命令前的等待毫秒数（默认 500）；</li>
 *   <li>{@code buyCommand}：到达后执行的购买命令模板，
 *       形如 {@code /qs amount {count}}，{@code {count}} 被替换为本次购买数量；</li>
 *   <li>{@code clickReachDist}：判定"准星可点击到告示牌"的最大距离（默认 4.0 方块）。
 *       默认 4.0 对应 MC 1.20.1 原版服务端方块交互距离上限约 4.5 格的安全余量；
 *       部分服务器/模组会修改该上限，可按实际情况调大，不做硬上限限制；</li>
 *   <li>{@code sightBlockedTimeoutTicks}：到店判定中视线被遮挡时的最大等待 tick 数（默认 60，
 *       超时后按"已到达"处理交由对准流程收尾，避免卡死）；</li>
 *   <li>{@code aimDegPerTick}：到店后转视角的每 tick 最大角度（默认 30°，值越小转头越"像人"）；</li>
 *   <li>{@code aimSettleTicks}：精确对准后、点击前的静置 tick 数（默认 2），
 *       给服务端留出接收新朝向的时间。</li>
 * </ul>
 *
 * <h3>存货（stash）相关</h3>
 * <p>QShop 在玩家背包空间不足时会<b>拒绝发货</b>，因此购买前必须预判容量，
 * 不足时先把背包里的东西存进箱子再回来买。</p>
 * <ul>
 *   <li>{@code stashEnabled}：是否启用自动存货（默认 true；关闭后容量不足只会告警并跳过）；</li>
 *   <li>{@code stashPositions}：存货箱坐标列表，格式 {@code dimension(x,y,z)}，
 *       与计划文件中的 position 同格式。箱子装满后按列表顺序<b>顺延</b>到下一个；</li>
 *   <li>{@code stashKeepItems}：存货时保留在背包里不搬走的物品 ID（工具、货币等）；</li>
 *   <li>{@code stashTransferDelayTicks}：每搬运一格之间的间隔 tick（默认 2，防止被服务端判定异常）；</li>
 *   <li>{@code stashReserveSlots}：容量预判时额外预留的空格数（默认 1，给服务器可能的赠品/找零留余地）。</li>
 * </ul>
 */
@Config(name = "qab_config")
public class QabConfig implements ConfigData {
    // ==================== 购买相关 ====================

    /** 到达后发送购买命令前的延时（毫秒）。 */
    public int buyDelayMs = 500;
    /** 购买命令模板，{@code {count}} 占位符替换为本次购买数量。 */
    public String buyCommand = "/qs amount {count}";
    /** 准星可点击告示牌的最大距离（方块）。 */
    public double clickReachDist = 4.0;
    /** 到店判定中视线被遮挡时的最大等待 tick 数；超时后按"已到达"处理交由对准流程收尾。 */
    public int sightBlockedTimeoutTicks = 60;
    /** 到店后转视角的每 tick 最大角度（度）。 */
    public float aimDegPerTick = 30.0F;
    /** 精确对准后、发起点击前的静置 tick 数。 */
    public int aimSettleTicks = 2;

    // ==================== 存货（stash）相关 ====================

    /** 是否启用自动存货。 */
    public boolean stashEnabled = true;
    /** 存货箱坐标列表，格式 {@code dimension(x,y,z)}，箱满时按顺序顺延。 */
    public List<String> stashPositions = new ArrayList<>();
    /** 存货时保留在背包中的物品 ID（不搬进箱子）。 */
    public List<String> stashKeepItems = new ArrayList<>();
    /** 每搬运一格之间的间隔 tick。 */
    public int stashTransferDelayTicks = 2;
    /** 容量预判时额外预留的空格数。 */
    public int stashReserveSlots = 1;

    // ==================== 区域相关 ====================

    /** 是否启用区域选择器（左/右键记录坐标）。 */
    public boolean regionSelectorMode = false;
    /** 是否渲染区域高亮边框。 */
    public boolean regionVisible = true;

    /**
     * 修复反序列化后可能出现的非法值 / null 集合。
     *
     * <p>AutoConfig 加载配置后回调；Gson 反序列化不经过字段初始值，
     * 集合字段可能为 null，必须在此兜底。校验上下限以内联字面量写出（见类 javadoc）。</p>
     */
    @Override
    public void validatePostLoad() {
        if (buyDelayMs < 0) buyDelayMs = 500;
        if (buyCommand == null || buyCommand.isBlank()) buyCommand = "/qs amount {count}";
        if (clickReachDist <= 0) clickReachDist = 4.0;
        // 视线被挡超时下限 20：太小会在绕路途中经过牌子附近时误判到达。
        if (sightBlockedTimeoutTicks < 20) sightBlockedTimeoutTicks = 60;

        // 转头速度范围 [5, 180]°/tick：过小对准迟迟完不成触发超时放弃，180 等价瞬间对准。
        if (aimDegPerTick < 5) {
            aimDegPerTick = aimDegPerTick <= 0 ? 30 : 5;
        } else if (aimDegPerTick > 180) {
            aimDegPerTick = 180;
        }
        // 静置 tick 上限 40：再久没有意义，只会拖慢购买。
        if (aimSettleTicks < 0) {
            aimSettleTicks = 2;
        } else if (aimSettleTicks > 40) {
            aimSettleTicks = 40;
        }

        if (stashPositions == null) {
            stashPositions = new ArrayList<>();
        } else {
            stashPositions.removeIf(s -> s == null || s.isBlank());
        }
        if (stashKeepItems == null) {
            stashKeepItems = new ArrayList<>();
        } else {
            stashKeepItems.removeIf(s -> s == null || s.isBlank());
        }

        // 搬运间隔上限 100：避免用户填个巨大值让存货永远跑不完。
        if (stashTransferDelayTicks < 0) {
            stashTransferDelayTicks = 2;
        } else if (stashTransferDelayTicks > 100) {
            stashTransferDelayTicks = 100;
        }

        // 预留格上限 26：主背包共 27 格，留满就没法买东西了。
        if (stashReserveSlots < 0) {
            stashReserveSlots = 1;
        } else if (stashReserveSlots > 26) {
            stashReserveSlots = 26;
        }
    }

    // ---- getters ----

    public int getBuyDelayMs() {
        return buyDelayMs;
    }

    public String getBuyCommand() {
        return buyCommand;
    }

    public double getClickReachDist() {
        return clickReachDist;
    }

    /** 到店判定中视线被遮挡时的最大等待 tick 数。 */
    public int getSightBlockedTimeoutTicks() {
        return sightBlockedTimeoutTicks;
    }

    /** 到店后转视角的每 tick 最大角度（度）。 */
    public float getAimDegPerTick() {
        return aimDegPerTick;
    }

    /** 精确对准后、发起点击前的静置 tick 数。 */
    public int getAimSettleTicks() {
        return aimSettleTicks;
    }

    public boolean isStashEnabled() {
        return stashEnabled;
    }

    /** 存货点列表（只读视图）。 */
    public List<String> getStashPositions() {
        return Collections.unmodifiableList(stashPositions);
    }

    /** 存货时保留在背包中的物品 ID（只读视图）。 */
    public List<String> getStashKeepItems() {
        return Collections.unmodifiableList(stashKeepItems);
    }

    public int getStashTransferDelayTicks() {
        return stashTransferDelayTicks;
    }

    public int getStashReserveSlots() {
        return stashReserveSlots;
    }

    /** 是否启用区域选择器（左/右键记录坐标）。 */
    public boolean isRegionSelectorMode() {
        return regionSelectorMode;
    }

    /** 设置区域选择器开关（由命令 /qab region selector 调用）。 */
    public void setRegionSelectorMode(boolean regionSelectorMode) {
        this.regionSelectorMode = regionSelectorMode;
    }

    /** 是否渲染区域高亮边框。 */
    public boolean isRegionVisible() {
        return regionVisible;
    }

    /** 设置区域高亮开关（渲染器会读取此值）。 */
    public void setRegionVisible(boolean regionVisible) {
        this.regionVisible = regionVisible;
    }

    /**
     * 追加一个存货点。
     *
     * @param position 位置字符串 {@code dimension(x,y,z)}
     * @return 成功追加返回 true；已存在相同点位返回 false
     */
    public boolean addStashPosition(String position) {
        if (position == null || position.isBlank()) return false;
        if (stashPositions.contains(position)) return false;
        stashPositions.add(position);
        return true;
    }

    /**
     * 移除一个存货点。
     *
     * @param position 位置字符串
     * @return 确实移除了返回 true
     */
    public boolean removeStashPosition(String position) {
        return position != null && stashPositions.remove(position);
    }

    /**
     * 按索引移除存货点。
     *
     * @param index 从 0 开始的下标
     * @return 被移除的位置字符串；下标越界返回 null
     */
    public String removeStashPositionAt(int index) {
        if (index < 0 || index >= stashPositions.size()) return null;
        return stashPositions.remove(index);
    }
}
