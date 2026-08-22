package com.billy65536.qab.gui;

import com.billy65536.qab.QShopAutoBuyMod;
import com.billy65536.qab.QShopAutoBuyer;
import com.billy65536.qab.planner.model.ShoppingItem;
import com.billy65536.qab.planner.model.ShoppingList;

import java.nio.file.Path;
import java.util.List;

/**
 * 购物清单的 {@link IListSource} 实现：包装内存中的 {@link ShoppingList} 与目标路径，
 * 保存时以 Gson pretty 格式写回 JSON（与命令层持久化约定一致）。
 *
 * <p>加载复用 {@link QShopAutoBuyer#loadShoppingList(Path)}，避免两处解析逻辑。
 * 支持临时模式：{@code path == null} 表示仅内存清单（从计划转换而来），
 * 此时 {@link #save()} 返回 false、{@link #isPersistable()} 返回 false，
 * 屏幕层据此禁用「保存」按钮。</p>
 */
public class ShoppingListSource implements IListSource<ShoppingItem> {

    private final ShoppingList list;
    private Path path;

    public ShoppingListSource(ShoppingList list, Path path) {
        this.list = list;
        this.path = path;
    }

    /**
     * 从指定路径加载清单并包装为数据源；解析失败时返回 null。
     */
    public static ShoppingListSource load(Path path) {
        ShoppingList list = QShopAutoBuyMod.BUYER.loadShoppingList(path);
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
    public void remove(int index) {
        List<ShoppingItem> items = list.getItems();
        if (items == null || index < 0 || index >= items.size()) return;
        items.remove(index);
    }

    @Override
    public boolean save() {
        // 临时内存清单不允许直接落盘（防止误覆盖正式文件），必须经 saveAs 另存
        if (path == null) {
            return false;
        }
        return QShopAutoBuyMod.BUYER.saveShoppingList(path, list);
    }

    @Override
    public String getName() {
        return list.getName();
    }

    @Override
    public String getDescription() {
        return list.getDescription();
    }

    @Override
    public boolean isPersistable() {
        return path != null;
    }

    @Override
    public boolean saveAs(String name) {
        Path target = QShopAutoBuyMod.BUYER.saveShoppingListAs(list, name);
        if (target == null) {
            return false;
        }
        // 另存成功后同步指向新路径，后续 save() 落到新位置
        this.path = target;
        return true;
    }

    @Override
    public void updateMeta(String name, String description) {
        list.setName(name);
        list.setDescription(description);
    }
}
