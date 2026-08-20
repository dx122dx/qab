package com.billy65536.qab.gui;

import com.billy65536.qab.QabCommands;
import com.billy65536.qab.planner.model.PlanEntry;
import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingList;
import com.billy65536.qab.planner.model.ShoppingPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物计划的 {@link IListSource} 实现：将 {@code plan.getPlan()} 按 itemId 聚合为
 * {@link ShoppingItem} 列表，供计划 GUI 的「编辑为购物清单」与标题改名/改描述使用。
 *
 * <p>聚合规则：{@code item.count = Σ(entry.count + entry.redundancy)}，即合并必需与冗余
 * 还原真实总需求。聚合出的清单为纯临时数据（全局 redundancy=0、multiplier=1，
 * 避免再次生成时重复叠加冗余），因此 {@link #isPersistable()} 恒为 false，
 * 只能经 {@link #saveAs(String)} 另存为正式清单。</p>
 *
 * <p>已知局限：{@link PlanEntry} 不含附魔/NBT/最大可购数等匹配条件，转换后
 * 购物清单条目的这些条件会丢失（置空），再次生成计划时按无条件匹配处理。</p>
 */
public class PlanListSource implements IListSource<ShoppingItem> {

    private final ShoppingPlan plan;
    /** 聚合出的临时清单（仅内存，用于 saveAs 委托与「编辑为购物清单」跳转）。 */
    private final ShoppingList list;

    public PlanListSource(ShoppingPlan plan) {
        this.plan = plan;
        this.list = new ShoppingList();
        this.list.setName(plan.getName());
        this.list.setDescription(plan.getDescription());
        this.list.setItems(aggregate(plan));
    }

    /**
     * 按 itemId 聚合计划条目：{@code count = Σ(count + redundancy)}；跳过无 itemId 的条目
     * （旧版本 1 计划无法还原物品，聚合为空列表）。
     *
     * @param plan 购物计划
     * @return 聚合后的物品列表（保持首次出现顺序）
     */
    public static List<ShoppingItem> aggregate(ShoppingPlan plan) {
        Map<String, ShoppingItem> byId = new LinkedHashMap<>();
        if (plan.getPlan() != null) {
            for (PlanEntry entry : plan.getPlan()) {
                if (!entry.hasItemId()) {
                    continue;
                }
                ShoppingItem item = byId.computeIfAbsent(entry.getItemId(), id -> new ShoppingItem(id, 0));
                item.setCount(item.getCount() + entry.getTotal());
            }
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public List<ShoppingItem> getItems() {
        return list.getItems();
    }

    @Override
    public int size() {
        return list.getItems() == null ? 0 : list.getItems().size();
    }

    @Override
    public void remove(int index) {
        // 计划视图无行内编辑，聚合清单不允许删除
    }

    @Override
    public boolean save() {
        // 临时内存清单不允许直接落盘（防误覆盖正式文件），必须经 saveAs 另存
        return false;
    }

    @Override
    public String getName() {
        return plan.getName();
    }

    @Override
    public String getDescription() {
        return plan.getDescription();
    }

    @Override
    public boolean isPersistable() {
        return false;
    }

    @Override
    public boolean saveAs(String name) {
        return QabCommands.saveShoppingListAs(this.list, name) != null;
    }

    @Override
    public void updateMeta(String name, String description) {
        plan.setName(name);
        plan.setDescription(description);
        list.setName(name);
        list.setDescription(description);
    }

    /**
     * 聚合出的临时购物清单（供「编辑为购物清单」构造临时
     * {@link ShoppingListSource} 跳转到购物清单 GUI）。
     */
    public ShoppingList getList() {
        return list;
    }
}
