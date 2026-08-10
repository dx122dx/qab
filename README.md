# QShop Auto Buy (QAB)

> QShop 自动购买辅助客户端模组 —— 基于 [Chunk Scanner](https://github.com/dx122dx/chunkscanner) 的扫描数据，自动规划并导航购买。

QAB 是一个 Fabric 客户端模组，工作于 Minecraft 1.20.1。它读取 Chunk Scanner 导出的 QShop 商店数据库，结合购物清单（可由原理图自动生成）计算出最优购买方案，并通过 Chunk Scanner 的导航门面自动寻路到各家商店、到达后自动执行购买命令。

## 功能概览

- **原理图 → 购物清单**：解析 `.litematic` / `.schem` / `.schematic` / `.nbt` 原理图，统计所需方块并生成清单（支持倍率、冗余、排除、排序等）。
- **清单 + 商店数据库 → 购买计划**：贪心算法按单价从低到高分配购买量，自动计算总成本，并标记无法满足的需求与冗余缺口。
- **计划 → 自动购买**：按计划逐店自动寻路（依赖 Chunk Scanner 导航），到达告示牌后自动执行购买命令；目标按维度匹配，跨维度目标等玩家自行前往后继续。

## 依赖

| 依赖 | 说明 |
| --- | --- |
| Minecraft | 1.20.1，客户端环境（environment = client） |
| Fabric Loader | >= 0.14.24 |
| Fabric API | 任意版本 |
| Java | >= 17 |
| Chunk Scanner | >= 1.1.0-dev-20260802.4（提供 QShop 数据库与导航门面） |
| schematic4j | 1.1.0（原理图解析，已 include 进 jar） |

> Chunk Scanner 的版本号由 `build.gradle` 自动从 `../chunkscanner/gradle.properties` 读取，无需手动同步。请保持 qab 与 chunkscanner 的相对目录位置（`../chunkscanner`），或在 `gradle.properties` 中调整 `chunkscanner_default_version` 兜底值。

## 构建

```bash
# 先构建 Chunk Scanner，使本地依赖可用
cd ../chunkscanner && sh ./gradlew build

# 再构建 QAB
cd ../qab && sh ./gradlew build
```

产物位于 `build/libs/`。

## 使用流程

完整的购买流程分为三步：选择数据库 → 生成/选择清单 → 生成计划 →（可选）自动执行。

### 1. 选择 QShop 数据库

数据库来自 Chunk Scanner 的导出 ZIP，默认位于游戏目录的 `chunkscanner/export/`：

```
/qab select db <文件名>
```

`<文件名>` 支持 Tab 自动补全（不含 `.zip` 后缀）。选择后会校验元数据与文件完整性，并报错/警告。

### 2. 生成或选择购物清单

**方式 A：从原理图生成**（推荐）

```
/qab generate list <原理图名> [配置...]
```

原理图默认放在游戏目录的 `schematics/`，支持自动补全。生成后自动选中该清单。

可选 `key=value` 配置（空格分隔，未知键/非法值仅告警）：

| 键 | 含义 | 默认 |
| --- | --- | --- |
| `name` | 清单名称 | 原理图文件名 |
| `desc` / `description` | 清单描述 | 自动生成 |
| `redundancy` | 每项额外冗余量 | 0 |
| `out` / `output` | 输出文件名（不含 .json） | 同 name |
| `multiplier` / `mult` | 数量倍率 | 1.0 |
| `min` | 单项最小数量（低于则提升到该值） | 不限制 |
| `threshold` | 单项数量阈值（低于则丢弃） | 1 |
| `blockentity` / `blockentities` | 是否统计容器**内部存放的物品**（箱子/漏斗等） | false |
| `deductinventory` / `deductinv` | 扣除背包（含潜影盒）已有物品，只列缺口 | false |
| `rawid` / `raw` | 保留原始方块 ID（不做方块→物品映射，调试用） | false |
| `exclude` / `excludes` | 排除的方块 ID，逗号分隔，支持 `*` 通配，可多次出现 | 无 |
| `sort` | `count`（数量降序，默认）/ `id`（ID 升序） | count |

示例：

```
/qab generate list my_house redundancy=64 multiplier=2 exclude=air,*_sign sort=id
/qab generate list my_house deductinventory=true blockentity=true
```

#### 统计规则

清单按**方块状态**精确计数，而非简单按方块 ID 累加：

| 情形 | 处理 |
| --- | --- |
| 双台阶 `type=double` | 计 2 个台阶 |
| 雪层 / 蜡烛 / 海泡菜 | 按 `layers` / `candles` / `pickles` 的数值计数 |
| 门、床、高草、向日葵的上半部（`half=upper`、`part=head`） | 跳过，避免数量翻倍 |
| 水 / 岩浆源方块（`level=0`） | 换算为水桶 / 岩浆桶 |
| 流动的水 / 岩浆（`level>0`） | 跳过 |
| 火、活塞头等技术性方块 | 无法购买，从清单剔除并在聊天栏提示 |

方块状态经 Minecraft 注册表还原为真实 `BlockState` 后判定，因此模组方块只要复用原版属性即可自动适配。原理图来自其他游戏版本时，无法识别的状态会被静默忽略而不影响整体生成。

`blockentity=true` 只统计容器**里装的物品**；容器方块本身始终由方块统计负责，两者不会重复计数。

`deductinventory=true` 会在应用倍率、最小值与阈值**之后**再扣除背包库存（先扣再放大会把已有量一并放大），已完全满足的物品不再列入清单。

**方式 B：使用已有清单**

```
/qab select list <清单名>
```

清单默认位于游戏目录的 `qab/list/`，支持自动补全（不含 `.json` 后缀）。

### 3. 生成购买计划

```
/qab plan [计划名]
```

计划写入游戏目录的 `qab/plan/<计划名>.json`（默认按时间戳命名）。回显包含计划条目数、总成本、失败项数与警告项数。规划算法：

1. 为每个清单项查找所有匹配的售卖模式商店；
2. 按单价升序排序，从最便宜的商店贪心分配；
3. `count`（必需量）优先满足，`redundancy`（冗余量）其次；
4. 必需量未满足 → 记入 `failed`；冗余量未满足 → 记入 `warn`。

匹配条件支持：物品 ID、附魔（`enchant`）、NBT 匹配（`matchNbt`）、最高可承受价格（`maxAffordable`）。

### 4. 自动寻路并购买

```
/qab nav apply <计划名>
```

依计划逐店寻路并购买。到达告示牌后，按配置延时执行购买命令。可直接传入 `qab/plan/` 下的文件名（不含 `.json`），或完整路径。

**维度匹配**：坐标只在其所属维度内有意义，因此只会投递位于**当前维度**的目标：

- 队列中优先执行本维度的目标，跨维度目标顺延到后面；
- 本维度目标全部完成后，若队列里还剩其他维度的目标，会提示所需维度并暂停等待——玩家自行传送过去即自动继续（不想等用 `/qab nav stop` 中止）。不自动跨维度寻路；
- 途中离开目标维度（传送门 / 传送指令），当前目标会被收回重新排队，不会朝新维度里的同名坐标乱跑；
- 存货点同样按维度过滤，全部存货点都不在当前维度时会提示并中止。

**容量感知（自动购买的核心行为）**：

1. 每次下单前按 `Item.getMaxCount()` 计算背包还能装下多少个该物品（考虑空格子数与已有同类堆的剩余空间）；
2. **装不下全部**就只买能装下的部分，剩余量自动回插队列——存货完成后会回来补齐，不会死板地一次买空或放弃；
3. 到达后若**一个都装不下**（背包满且无保留物品），自动触发存货流程：导航到最近的存货点 → 主动开箱 → 逐格搬运（保留列表中的物品不搬）→ 箱子装满则顺延配置里的下一个存货点 → 全部失败则中止购买并提示；
4. 存货可用 `/qab stash add|list|remove` 管理，或直接编辑 `qab.json` 的 `stashPositions`。

> 计划格式版本 1（旧）缺少 `itemId`，无法做容量预判，`/qab nav apply` 会直接拒绝并提示重新生成。

## 配置文件

### `config/qab/qab.json`

QAB 运行时配置（文件不存在时使用默认值）：

| 字段 | 说明 | 默认 |
| --- | --- | --- |
| `buyDelayMs` | 到达告示牌后、发送购买命令前的等待毫秒数 | 500 |
| `buyCommand` | 到达后执行的购买命令模板，`{count}` 被替换为<b>本次实际购买量</b>（可能因容量限制少于计划） | `/qs amount {count}` |
| `clickReachDist` | 判定"准星可点击到告示牌"的最大距离（方块） | 5.0 |
| `stashEnabled` | 是否启用背包满时自动存货 | true |
| `stashPositions` | 存货点坐标列表（格式 `维度(x,y,z)`），按顺序使用，箱子装满则顺延下一个 | `[]` |
| `stashKeepItems` | 存货时<b>不搬运</b>的物品 ID 列表（如工具、货币） | `[]` |
| `stashTransferDelaySec` | 每搬一格物品的冷却秒数（反刷屏） | 0.15 |
| `stashReopenDelaySec` | 开箱失败后的重试间隔秒数 | 1.0 |
| `stashReopenMaxTries` | 开箱最大重试次数 | 10 |
| `stashCapacityThreshold` | 剩余空格数低于此值触发存货（设为 0 表示满了才去） | 0 |

示例：

```json
{
  "buyDelayMs": 500,
  "buyCommand": "/qs amount {count}",
  "clickReachDist": 5.0,
  "stashEnabled": true,
  "stashPositions": [
    "minecraft:overworld(60,71,-120)",
    "minecraft:overworld(64,71,-118)"
  ],
  "stashKeepItems": ["minecraft:diamond_pickaxe", "minecraft:emerald"],
  "stashTransferDelaySec": 0.15
}
```

> **存货点管理命令**（无需手写 JSON）：
> - `/qab stash add` — 把准星所指的方块记为存货点（需在 6 格内）；
> - `/qab stash list` — 列出所有已配置存货点及其序号；
> - `/qab stash remove <序号>` — 按序号移除存货点。
>
> 命令会自动持久化到 `qab.json`。

### `config/qab/block-mapping.json`

方块 → 物品映射配置，覆盖内置默认表（每次 `/qab generate list` 时实时重新加载，改 JSON 无需重启）：

- `unobtainable`：无法购买的方块 ID 列表（火、活塞头等），取内置与配置的并集；
- `irregular`：不规则单映射 `方块ID: 物品ID`，按 key 覆盖内置并可追加；
- `composite`：组合方块 `方块ID: [物品ID, ...]`。

这三张表的优先级**高于**内置的方块状态特例，因此可用于覆盖默认行为。

> 注意：水与岩浆不在 `unobtainable` 中——它们由状态规则按 `level` 判定，源方块换算为对应的桶。若希望完全不购买液体，可在配置中把 `minecraft:water` / `minecraft:lava` 加入 `unobtainable`。

文件不存在或解析失败时回退内置默认表并告警。

## 文件结构与路径约定

| 路径 | 用途 |
| --- | --- |
| `chunkscanner/export/*.zip` | Chunk Scanner 导出的 QShop 数据库（select db 来源） |
| `schematics/` | 原理图文件（generate list 来源） |
| `qab/list/*.json` | 购物清单 |
| `qab/plan/*.json` | 购买计划 |
| `config/qab/qab.json` | 运行时配置 |
| `config/qab/block-mapping.json` | 方块→物品映射配置 |

## 数据格式

**购物清单**（`qab/list/*.json`）：

```json
{
  "version": 1,
  "name": "my_house",
  "description": "Generated from my_house.litematic ...",
  "redundancy": 0,
  "items": [
    { "id": "minecraft:stone", "count": 64 },
    { "id": "minecraft:oak_planks", "count": 32, "enchant": {"minecraft:unbreaking": 3} }
  ]
}
```

**购买计划**（`qab/plan/*.json`，当前格式版本 **2**）：

```json
{
  "version": 2,
  "totalCost": 123.0,
  "failed": [ { "item": { "id": "minecraft:diamond", "count": 5 }, "count": 5, "redundancy": 0 } ],
  "warn": [],
  "plan": [
    { "position": "minecraft:overworld(12,65,-13)", "itemId": "minecraft:stone", "count": 64, "redundancy": 0 }
  ]
}
```

> 版本 2 起每条计划新增 `itemId`（商品的物品 ID），供自动购买查询堆叠上限做背包容量预判。旧版本计划无法用于 `/qab nav apply`，需重新生成。

## 模块结构

- `config`：运行时配置（`QabConfig`）与方块映射配置（`BlockMappingConfig`）。
- `generator`：原理图解析与清单生成（`SchematicListGenerator` / `BlockItemResolver` / `ListGenConfig`）。
- `integration`：对接 Chunk Scanner（数据库加载 `CsQShopDbLoader`、导航门面 `CsNavigationHelper`）、购买编排（`ShoppingRunner` 单目标投递 + 容量预判 + 部分购买回插）、存货（`StashRoutine` 状态机 + `InventoryCapacityCalculator` 容量计算 + `BlockAimHelper` 方块对准公共工具）。
- `planner`：购物规划与领域模型（`ShoppingPlanner` 及 `model` 包）。

## 许可证

AGPL-3.0-only

## 作者

billy65536
