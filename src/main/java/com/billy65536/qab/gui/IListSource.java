package com.billy65536.qab.gui;

import java.util.List;

/**
 * 列表类数据源抽象：{@link ShoppingListScreen} 只依赖本接口渲染与操作列表，
 * 不关心具体数据来自购物清单还是将来的购物计划，便于复用同一套查看/编辑界面。
 *
 * <p>行操作（拖拽移动/拖出删除）与保存均委托给数据源实现，
 * 屏幕层不直接触碰持久化细节。</p>
 *
 * @param <T> 列表元素类型（如 {@link com.billy65536.qab.planner.model.ShoppingItem}）
 */
public interface IListSource<T> {

    /**
     * 返回全部元素（顺序即显示顺序）。
     */
    List<T> getItems();

    /**
     * 元素个数。
     */
    int size();

    /**
     * 将指定下标的元素移动到 {@code to} 位置。
     * <p>{@code to} 为「移除前」的目标位置（0 ≤ to ≤ size），即元素移动后
     * 最终出现在列表的 {@code to} 处；语义等价于 {@code remove(from)} 后
     * 按「原目标位置」重新插入，天然正确处理 from &lt; to 时下标前移的问题。</p>
     * <p>默认实现采用 remove+add：from 越界或 to 越界时无操作；
     * 原地移动（to == from 或 to == from + 1，后者表示移到自身之后一位）不触发。</p>
     *
     * @param from 原下标
     * @param to   移除前的目标位置
     */
    default void move(int from, int to) {
        List<T> items = getItems();
        if (items == null || from < 0 || from >= items.size()) return;
        if (to < 0 || to > items.size()) return;
        if (to == from || to == from + 1) return;
        T item = items.remove(from);
        items.add(to > from ? to - 1 : to, item);
    }

    /**
     * 移除指定下标的元素；下标越界时无操作。
     */
    void remove(int index);

    /**
     * 将当前内存态写回持久层。
     *
     * @return 是否写回成功
     */
    boolean save();
}
