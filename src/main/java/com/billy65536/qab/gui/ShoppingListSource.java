package com.billy65536.qab.gui;

import com.billy65536.qab.QabCommands;
import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingList;

import java.nio.file.Path;
import java.util.List;

/**
 * 购物清单的 {@link IListSource} 实现：包装内存中的 {@link ShoppingList} 与目标路径，
 * 保存时以 Gson pretty 格式写回 JSON（与命令层持久化约定一致）。
 *
 * <p>加载复用 {@link QabCommands#loadShoppingList(Path)}，避免两处解析逻辑。
 * 将来「计划查看/编辑」可新增独立的 IListSource 实现复用同一套界面。</p>
 */
public class ShoppingListSource implements IListSource<ShoppingItem> {

    private final ShoppingList list;
    private final Path path;

    public ShoppingListSource(ShoppingList list, Path path) {
        this.list = list;
        this.path = path;
    }

    /**
     * 从指定路径加载清单并包装为数据源；解析失败时返回 null。
     */
    public static ShoppingListSource load(Path path) {
        ShoppingList list = QabCommands.loadShoppingList(path);
        if (list == null) return null;
        return new ShoppingListSource(list, path);
    }

    public ShoppingList getList() {
        return list;
    }

    public Path getPath() {
        return path;
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
    public void moveUp(int index) {
        List<ShoppingItem> items = list.getItems();
        if (items == null || index <= 0 || index >= items.size()) return;
        ShoppingItem tmp = items.get(index - 1);
        items.set(index - 1, items.get(index));
        items.set(index, tmp);
    }

    @Override
    public void moveDown(int index) {
        List<ShoppingItem> items = list.getItems();
        if (items == null || index < 0 || index >= items.size() - 1) return;
        ShoppingItem tmp = items.get(index + 1);
        items.set(index + 1, items.get(index));
        items.set(index, tmp);
    }

    @Override
    public void remove(int index) {
        List<ShoppingItem> items = list.getItems();
        if (items == null || index < 0 || index >= items.size()) return;
        items.remove(index);
    }

    @Override
    public boolean save() {
        return QabCommands.saveShoppingList(path, list);
    }
}
