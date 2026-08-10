package com.billy65536.qab.generator;

import net.sandrohc.schematic4j.schematic.types.SchematicBlockEntity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ObjLongConsumer;

/**
 * 统计原理图中<b>容器内部存放的物品</b>（箱子、漏斗、发射器、潜影盒等）。
 *
 * <h3>这解决了什么问题</h3>
 * <p>方块实体的<b>容器方块本身</b>（如箱子）在方块遍历阶段就已经统计过了。
 * 若在方块实体阶段再按 {@code SchematicBlockEntity.name()} 加一次，
 * 就等于把箱子数了两遍。本类只统计容器<b>里面装的东西</b>，
 * 与方块统计各司其职，互不重叠。
 *
 * <h3>数据形态</h3>
 * <p>schematic4j 的 {@code extra()} 已经过
 * {@code TagUtils.unwrap()} 递归解包，NBT 结构被转成了<b>纯 Java 类型</b>：
 * {@code Items} 是 {@code List<Map<String,Object>>}，每项含 {@code id}（String）
 * 与 {@code Count}（Byte，因为原版用 byte 存堆叠数）。
 * 因此无需接触任何 NBT 类，也<b>不能</b>用 {@code ItemStack.fromNbt}
 * ——schematic4j 的 NBT 类型与 Minecraft 的并非同一套。
 *
 * <p>原理图可能来自任意版本、甚至被手工编辑过，任何字段都可能缺失或类型不符，
 * 故所有读取均做防御式处理，畸形条目静默跳过而非中断整个清单生成。
 */
public final class ContainerItemCounter {

    /** 容器内含物列表的 NBT 键。 */
    private static final String KEY_ITEMS = "Items";
    /** 物品 ID 键。 */
    private static final String KEY_ID = "id";
    /** 堆叠数量键。 */
    private static final String KEY_COUNT = "Count";

    private ContainerItemCounter() {
    }

    /**
     * 统计单个方块实体内部存放的物品。
     *
     * @param be       方块实体，可为 null
     * @param consumer 接收 (物品 ID, 数量) 的回调
     * @return 成功统计的条目数（畸形/空条目不计）
     */
    public static int count(SchematicBlockEntity be, ObjLongConsumer<String> consumer) {
        if (be == null) {
            return 0;
        }
        Map<String, Object> extra = be.extra();
        if (extra == null || extra.isEmpty()) {
            return 0;
        }

        Object rawItems = extra.get(KEY_ITEMS);
        if (!(rawItems instanceof List<?> itemList)) {
            // 非容器方块实体（告示牌、刷怪笼等）没有 Items 字段，属正常情况
            return 0;
        }

        int counted = 0;
        for (Object rawEntry : itemList) {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                continue;
            }
            String itemId = normalizeItemId(entry.get(KEY_ID));
            if (itemId == null) {
                continue;
            }
            long amount = toCount(entry.get(KEY_COUNT));
            if (amount <= 0) {
                continue;
            }
            consumer.accept(itemId, amount);
            counted++;
        }
        return counted;
    }

    /** 规范化物品 ID：补全命名空间；非法值返回 null。 */
    private static String normalizeItemId(Object raw) {
        if (!(raw instanceof String s)) {
            return null;
        }
        String id = s.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.equals("minecraft:air")) {
            return null;
        }
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /**
     * 读取堆叠数量。原版以 byte 存储，但不同格式/版本可能是任意数值类型，
     * 故统一按 {@link Number} 处理；缺失时按 1 计（NBT 中 Count 可省略）。
     */
    private static long toCount(Object raw) {
        if (raw == null) {
            return 1L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
