package com.billy65536.qab.gui;

import java.util.List;

/**
 * 列表类数据源抽象：{@link ShoppingListScreen} 只依赖本接口渲染与操作列表，
 * 不关心具体数据来自购物清单还是将来的购物计划，便于复用同一套查看/编辑界面。
 *
 * <p>行操作（上移/下移/删除）与保存均委托给数据源实现，
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
     * 将指定下标的元素上移一位；下标越界或已在首位时无操作。
     */
    void moveUp(int index);

    /**
     * 将指定下标的元素下移一位；下标越界或已在末位时无操作。
     */
    void moveDown(int index);

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
