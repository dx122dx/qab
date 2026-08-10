# 材料清单生成（`/qab generate list`）

本文说明 qab 如何把原理图解析成购物清单，重点是**数量为什么是这个数**。
实现位于 `com.billy65536.qab.generator` 包。

## 流水线

```
原理图文件
  → schematic4j 解析
  → 遍历方块：BlockStateResolver 还原状态 → BlockStateRules 判定 → BlockItemResolver 映射物品
  → 遍历方块实体：ContainerItemCounter 统计容器内含物（可选）
  → 聚合计数
  → 应用倍率 / 最小值 / 阈值
  → 扣除玩家库存（可选）
  → 排序输出 ShoppingList
```

**阶段顺序是有意为之**：库存扣除必须排在倍率之后。倍率放大的是「需求」，
若先扣库存再放大，玩家已有的部分会被一并放大，导致买多。

## 类职责

| 类 | 职责 |
| --- | --- |
| `BlockStateResolver` | 把「方块 ID + 状态字符串」还原为真实的 `BlockState` |
| `BlockStateRules` | 基于 `BlockState` 判定「跳过 / 数量倍数 / 换成别的物品」 |
| `BlockItemResolver` | 方块 → 可购买物品的映射链，带解析缓存 |
| `ContainerItemCounter` | 统计容器方块实体内部存放的物品 |
| `PlayerInventoryCounter` | 统计玩家背包（含潜影盒）已持有的物品 |
| `SchematicListGenerator` | 编排上述组件，产出 `ShoppingList` |
| `ListGenConfig` | 解析 `key=value` 配置 |

## 为什么要还原 BlockState

早期实现直接把方块状态串 `[...]` 剥掉，只按方块 ID 计数，导致三类错误：

1. **数量偏少**：双台阶 `type=double` 实际用了 2 个台阶，却只算 1 个；
   `snow[layers=5]` 需要 5 个雪块，也只算 1 个。
2. **数量翻倍**：门、床、高草、向日葵占两格，两格是**同一个**物品，
   但两格都被计数，结果买成双份。
3. **买到买不到的东西**：流动的水/岩浆是源方块蔓延的产物，不需要单独购买。

改为还原 `BlockState` 后，判定依据是方块**自己声明的属性**
（`Properties.SLAB_TYPE`、`Properties.LAYERS` 等），而不是死记的字符串。
这样模组方块只要复用原版属性就能自动被正确处理，无需为每个模组硬编码。

## 状态规则表

| 属性 | 条件 | 处理 |
| --- | --- | --- |
| `DOUBLE_BLOCK_HALF` | `UPPER` | 跳过（与下半部共用一个物品） |
| `BED_PART` | `HEAD` | 跳过（与床尾共用一个物品） |
| `LEVEL_15`（水/岩浆） | `0` | 换算为对应的桶 |
| `LEVEL_15`（水/岩浆） | `>0` | 跳过（流动部分） |
| `SLAB_TYPE` | `DOUBLE` | ×2 |
| `LAYERS` | 任意 | ×层数 |
| `CANDLES` | 任意 | ×蜡烛数 |
| `PICKLES` | 任意 | ×海泡菜数 |

## 解析优先级

由高到低，逐级回退：

1. **状态级跳过** —— 上半部、床头、流动液体
2. **`unobtainable`** —— 用户可配，火/活塞头等
3. **`irregular`** —— 用户可配的不规则单映射
4. **`composite`** —— 用户可配的组合方块
5. **状态级特例** —— 液体源→桶、双台阶 ×2、按数量堆叠
6. **名称规则** —— `potted_` / `candle_cake` / `wall_` 变体
7. **注册表兜底** —— `block.asItem()`

用户配置的三张表（2–4）**刻意排在状态特例之前**，保证玩家始终能覆盖内置行为。

注册表兜底排在名称规则**之后**，是因为墙上变体（如 `wall_torch`）的
`asItem()` 会返回 `AIR`，必须先由名称规则处理。

## 容器内含物

容器方块本身（箱子、漏斗）在**方块遍历**阶段就已统计。
方块实体阶段只统计容器**里装的物品**，两条路径不重叠。

schematic4j 的 `extra()` 经 `TagUtils.unwrap()` 递归解包，
NBT 已转成纯 Java 类型：`Items` 是 `List<Map<String,Object>>`，
`Count` 是 `Byte`。因此**不能**用 `ItemStack.fromNbt`
——schematic4j 的 NBT 类与 Minecraft 的并非同一套。

## 库存扣除

统计玩家全部槽位（主背包 + 快捷栏 + 盔甲 + 副手），并**下潜一层**统计潜影盒内含物。
只下潜一层：原版不允许潜影盒套潜影盒，更深的嵌套只可能来自异常数据，
无限递归反而有栈溢出风险。

与 `InventoryCapacityCalculator` 的区别：那个类算「还能装多少」（只看主背包 27 格），
本类算「已经有多少」（必须含快捷栏，因为那里的建材同样是已有库存）。

## 性能

解析结果按「方块 ID + 状态」缓存在 `BlockItemResolver` 中。
原理图里同状态方块高度重复（数万方块通常只有几十种状态组合），
缓存命中率极高，状态还原的开销可忽略。

缓存必须在 `BlockMappingConfig.reload()` 时清空，
否则玩家改了 JSON 却仍读到旧结果。

## 已知局限

- **跨版本原理图**：schematic4j 不做数据版本升级。旧版本原理图中已改名的方块或状态
  在当前版本可能无法识别，此时对应属性被静默忽略（保留默认值），
  方块本身仍按 ID 尽力解析。
- **不比对世界现状**：清单统计的是原理图的完整需求，
  不会检查目标位置是否已经放好了方块。
- **含水方块**：`waterlogged=true` 的方块只计其本体，不额外计水桶。
