# 实体 API 设计

## 范围

Lua 侧访问竞技场世界内实体的完整接口：取得实体句柄、读取状态、移动、生成、删除、数量查询与事件。

首版不包含：范围查询与按类型列出实体、背包与属性读写、AI 目标、乘客、NBT、载具 / 弹射物 / 展示实体的专属函数。这些在句柄树里预留位置，接口另行设计。

公开 API 的权威描述在落地后以 `文档/通用/LuaAPI.md` 为准；本文只描述设计决定与语义边界。

## 概念

实体用 UUID 标识。UUID 是实体的稳定身份：区块卸载重载、非玩家跨维度、玩家重生都会换掉服务端对象，UUID 不变。

**实体句柄**绑定在某次竞技场借用上，代表某个 UUID 与某个预期实体类型。对非玩家实体，竞技场世界同时是解析范围；玩家句柄只用竞技场做权限与目标世界上下文，解析范围是全局在线状态。句柄只保存竞技场句柄、UUID、预期类型，不保存实体实例或坐标；坐标会变，UUID 不会，定位符只能是 UUID。

**玩家也是实体**，但玩家句柄单独一根，不与非玩家句柄共根。原因是玩家的解析范围、修改权、可执行操作与非玩家实体不同。

实体句柄与全局表 `player` 的分工：

| 入口 | 语义 | 能力 |
|------|------|------|
| `player.*` | 全局、值语义（UUID 字符串），不依赖竞技场 | 查询位置、队伍、在线状态、比赛状态 |
| 玩家实体句柄 | 绑定在某次竞技场借用上的引用 | 读取、传送；后续可扩展玩家状态操作 |

同一个 UUID 可以创建多个句柄，彼此独立。句柄表不是值，不参与相等比较。

## 句柄体系

两个根类，不用接口，单继承：

```text
PlayerHandle                         玩家（无子类）
EntityHandle                         非玩家实体根
├── LivingEntityHandle               活体
│   ├── ArmorStandHandle             盔甲架
│   └── MobHandle                    生物
│       ├── MonsterHandle            敌对生物
│       └── AnimalHandle             动物
├── ItemHandle                       掉落物
└── PaintingHandle                   画
```

`PlayerHandle` 称为基类，但没有子类；它不继承 `EntityHandle`。两组句柄各自实现一组公共读取函数，换取玩家与非玩家在权限、解析范围上的彻底分离。

后续扩展直接挂在新节点上：`VehicleHandle`、`ProjectileHandle`、`DisplayHandle` 都继承 `EntityHandle`，不改变已有句柄类的函数表。

### 自动选类

句柄类只由**预期类型**决定，不看世界状态。预期类型必须是具体实体键（`"zombie"` 或 `"minecraft:zombie"`），不接受类别名、选择器、通配符。不传类型不允许。

判定顺序：

1. `minecraft:player` → `PlayerHandle`
2. `minecraft:armor_stand` → `ArmorStandHandle`
3. 实体类实现 `Painting` → `PaintingHandle`
4. 实体类实现 `Item` → `ItemHandle`
5. 实体类实现 `Monster` → `MonsterHandle`
6. 实体类实现 `Animals` → `AnimalHandle`
7. 实体类实现 `Mob` → `MobHandle`
8. 实体类实现 `LivingEntity` → `LivingEntityHandle`
9. 其余 → `EntityHandle`

第 2 步按实体键精确匹配，第 3～8 步只看 `EntityType` 对应的实体类，不实例化实体。`EntityType` 没有实体类时落到 `EntityHandle`。

预期类型同时是**断言**：使用句柄时要求当前实体的类型与预期类型完全相等，不是“属于同一大类”。写 `"zombie"` 只指僵尸，不能拿同一句柄去操作骷髅。

## 获取句柄

### `arena.entity_by_uuid(uuid, type)`

返回实体句柄表。

- `uuid`：UUID 字符串，非法抛错
- `type`：实体键，未知类型抛错；查询不限制可生成性，只有 `arena.spawn` 才要求类型可生成
- 创建时检查：借据有效、竞技场世界就绪（世界未加载时按管理器规则加载）
- 创建时**不查找实体**，也不检查实体是否存在

句柄创建后，除 `uuid()` / `type()` 这两项句柄自身信息外，其余函数在使用时重新按 UUID 查找；找不到、已死亡、类型不符都抛 Lua 错误，只有 `exists()` 返回 `false` 而不抛错。

### `arena.find_entity(uuid, type)`

按同样的参数创建句柄，但创建前先做一次存在性检查：

- 找到且类型匹配 → 返回句柄
- 找不到 / 已死亡 / 类型不符 → 返回 `nil`
- 参数错误（UUID 非法、类型未知）→ 抛错

`find_entity` 是获取句柄前的优雅检查；`entity_by_uuid` 是不检查的直取。两者返回的句柄语义相同。

### 存在性

非玩家实体只在**已加载区块**里可查。`World#getEntity(UUID)` 不加载区块，所以：

- 实体所在区块未加载 → 查不到，`exists()` 返回 `false`，读取抛错，`find_entity` 返回 `nil`
- 这不等同于实体被删除；区块重新加载后，同一个 UUID 的句柄恢复可用

玩家句柄的解析范围不同：按在线状态判定，与所在世界、区块无关。在线即存在，离线即不存在。玩家类型也不要求玩家当前在该竞技场世界内；句柄上的竞技场只用于借用校验与跨世界传送的目标世界。

句柄不提供“句柄有效性”概念。句柄始终代表那个 UUID 和那个预期类型；能不能读到东西由实体当前是否存在决定，表现为返回布尔（`exists()`）或抛错（其余读取）。

## 句柄函数

### 非玩家公共函数

| 函数 | 返回 | 说明 |
|------|------|------|
| `entity.uuid()` | 字符串 | UUID；不访问世界，实体消失也返回 |
| `entity.type()` | 字符串 | 预期实体键；不访问世界，实体消失也返回 |
| `entity.exists()` | 布尔 | 当前能否按 UUID 找到、类型是否匹配、是否存活；不抛错 |
| `entity.pos()` | 坐标表 | 当前浮点坐标；实体不存在即抛错 |
| `entity.rotation()` | 朝向表 | `{yaw=, pitch=}`；实体不存在即抛错 |
| `entity.teleport(pos[, rot])` | 无 | 在本世界内传送；`rot` 省略保留当前朝向 |
| `entity.teleport_to(arena, pos[, rot])` | 无 | 传送到另一竞技场世界 |
| `entity.remove()` | 无 | 直接移除，不走死亡流程 |

`LivingEntityHandle` 追加：

| 函数 | 返回 | 说明 |
|------|------|------|
| `entity.health()` | 数字 | 当前生命值 |
| `entity.set_health(n)` | 无 | 设置当前生命值；超过上限按上限处理，非法数字抛错 |
| `entity.kill()` | 布尔 | 走正常死亡流程（掉落、死亡事件）；返回调用后是否已进入死亡状态 |
| `entity.invisible()` / `entity.set_invisible(b)` | 布尔 / 无 | 是否隐身 |

`MobHandle` 追加：

| 函数 | 返回 | 说明 |
|------|------|------|
| `entity.ai()` / `entity.set_ai(b)` | 布尔 / 无 | 是否启用 AI |
| `entity.baby()` / `entity.set_baby(b)` | 布尔 / 无 | 是否幼年；目标不支持成长时抛错 |

`MonsterHandle`、`AnimalHandle` 首版不追加函数，先作为类型节点存在。

`ArmorStandHandle` 追加：

| 函数 | 返回 | 说明 |
|------|------|------|
| `entity.arms()` / `entity.set_arms(b)` | 布尔 / 无 | 是否显示手臂 |
| `entity.small()` / `entity.set_small(b)` | 布尔 / 无 | 是否小型 |
| `entity.marker()` / `entity.set_marker(b)` | 布尔 / 无 | 是否标记模式 |
| `entity.base_plate()` / `entity.set_base_plate(b)` | 布尔 / 无 | 是否有底座 |

`ItemHandle` 追加：

| 函数 | 返回 | 说明 |
|------|------|------|
| `entity.item()` | 物品堆描述符 | `{item = 材质键, count = 数量}` |
| `entity.set_item(物品堆描述符)` | 无 | 替换掉落物内容 |

`PaintingHandle` 追加：

| 函数 | 返回 | 说明 |
|------|------|------|
| `entity.art()` | 字符串 | 画作键，如 `"minecraft:kebab"` |
| `entity.facing()` | 字符串 | 附着方向，`"north"` / `"south"` / `"east"` / `"west"` |

### 玩家句柄函数

表内函数与全局表 `player` 无关；句柄变量名由脚本自定，下表用 `handle` 避免混淆。

| 函数 | 返回 | 说明 |
|------|------|------|
| `handle.uuid()` | 字符串 | UUID |
| `handle.type()` | 字符串 | 固定为 `"minecraft:player"` |
| `handle.exists()` | 布尔 | 是否在线 |
| `handle.pos()` | 坐标表 | 当前位置；离线即抛错 |
| `handle.rotation()` | 朝向表 | 当前朝向；离线即抛错 |
| `handle.teleport(pos[, rot])` | 无 | 在玩家当前世界内传送 |
| `handle.teleport_to(arena, pos[, rot])` | 无 | 传送到指定竞技场世界 |

玩家句柄没有 `remove` / `kill`。

### 传送语义

`teleport(pos[, rot])` 只在本世界内移动。`teleport_to(arena, pos[, rot])` 跨世界：

- 目标世界必须是目标竞技场当前借用的世界
- 目标区块未加载时先加载；传送后不卸载（实体必须留在目标位置）
- 传送失败（服务端拒绝、事件取消等）抛错
- 非玩家实体跨世界后，原句柄对应的世界已没有这个 UUID，原句柄失效；需要在新世界的竞技场句柄上重新获取
- 玩家跨世界后 UUID 不变，原句柄仍可用（玩家解析与所在世界无关）

`rot` 省略时保留当前朝向。`pos` 是浮点坐标表。

## 竞技场句柄的实体入口

| 函数 | 返回 | 说明 |
|------|------|------|
| `arena.entity_by_uuid(uuid, type)` | 实体句柄 | 直取，不检查实体是否存在 |
| `arena.find_entity(uuid, type)` | 实体句柄或 `nil` | 先做存在性检查 |
| `arena.spawn(pos, descriptor)` | UUID 字符串 | 生成实体，返回新实体 UUID |
| `arena.entity_count()` | 数字 | 当前已加载实体数 |

竞技场句柄不提供玩家操作函数。玩家与实体的移动、移除等操作统一由实体句柄承担；竞技场句柄只负责发放句柄、生成与计数。

## 生成

### `arena.spawn(pos, descriptor)`

返回新实体的 UUID 字符串。`pos` 是浮点坐标表；需要句柄时用 `arena.entity_by_uuid(uuid, type)` 或 `arena.find_entity(uuid, type)` 再取。

`descriptor` 是实体描述符表，形状参考方块描述符：顶层只放 `type`、`rot`、`name`，其余数据放 `data`。`type` 的命名空间可省略。

```lua
local uuid = arena.spawn({x = 10.5, y = 65, z = -3.5}, {
    type = "zombie",
    rot = {yaw = 90, pitch = 0},
    name = {text = "守卫"},
    data = {
        ai = false,
        baby = false,
        silent = true,
        glowing = true,
        invulnerable = true,
        gravity = true,
        persistent = true,
        name_visible = true,
        invisible = false,
        equipment = {
            mainhand = {item = "iron_sword", count = 1},
            head = {item = "iron_helmet", count = 1},
        },
    },
})
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `type` | 是 | 实体键；必须是可生成的非玩家实体 |
| `rot` | 否 | 朝向表；省略为 `{yaw = 0, pitch = 0}` |
| `name` | 否 | 自定义名称，文本组件描述符 |
| `data` | 否 | 类型专属数据表，字段见下 |

`data` 字段：

| 字段 | 适用类型 | 说明 |
|------|----------|------|
| `ai` | `Mob` | 是否启用 AI，默认 `true` |
| `baby` | `Ageable` | 是否幼年，默认 `false` |
| `silent` / `glowing` / `invulnerable` / `gravity` / `persistent` | 全部 | 对应实体属性；`persistent` 默认 `true`，保证生成到未加载区块后能被存档保留 |
| `name_visible` | 全部 | 自定义名称是否可见 |
| `invisible` | `LivingEntity` | 是否隐身 |
| `equipment` | `LivingEntity` | 装备表：`mainhand` / `offhand` / `head` / `chest` / `legs` / `feet`，每项物品堆描述符 |
| `item` | 掉落物 | 掉落物内容，掉落物生成时必填 |
| `painting` | 画 | `{art = 画作键, facing = 朝向}`，两字段均可选 |
| `armor_stand` | 盔甲架 | `{arms =, small =, marker =, base_plate =}`，均为布尔 |

物品堆描述符首版沿用方块容器物品的形状：`{item = 材质键, count = 数量}`，`count` 省略为 1。

能力不符在生成前抛错，比如给僵尸写 `painting`、给掉落物不写 `item`、给非幼年实体写 `baby`。不在生成后才发现字段无效。

### 生成流程与失败清理

1. 校验借据、世界就绪、描述符语法与能力
2. 坐标必须在该竞技场世界内：X/Z 在世界边界内，Y 在世界高度范围内
3. 检查实体数量上限
4. 目标区块未加载时先加载
5. 生成实体并应用描述符
6. 任一步在实体已产生后失败，先移除该实体，再抛 Lua 错误
7. 目标区块原本未加载时，生成完成后卸载并保存

第 7 步有一个直接后果：生成到未加载区块的实体，在区块再次加载前无法被句柄找到，`find_entity` 返回 `nil`，读取抛错。需要后续操作的实体应生成在已加载区域。

生成被服务端事件取消时按失败处理并抛错。

玩家不能通过 `arena.spawn` 生成；`type` 为 `player` 时直接抛错。

画和掉落物有独立规则：

- 画：`art` 省略时由服务端选择能放下的画作；指定画作放不下时移除已生成实体并抛错
- 掉落物：`data.item` 必填，缺失时在生成前抛错

### 数量上限

每个竞技场世界的已加载实体数上限为 **8192**。达到上限时 `arena.spawn` 抛错，不生成任何实体。

### `arena.entity_count()`

返回该竞技场世界当前的已加载实体数，口径与服务端 `World#getEntityCount()` 一致。未加载区块中的实体不计入。

服务端未在 API 契约里声明该函数的复杂度。按最坏情况对待：开销可能随实体数线性增长，不适合每 tick 调用；需要高频判断时由脚本缓存结果或降低调用频率。

## 删除

`remove` 与 `kill` 分开：

| 函数 | 行为 | 适用 |
|------|------|------|
| `remove()` | 直接从世界移除，无掉落、无死亡事件 | 全部非玩家实体 |
| `kill()` | 走正常死亡流程，掉落与死亡事件照常；受无敌、伤害事件取消等影响 | `LivingEntityHandle` |

实体已不存在时两者都抛错；调用方先用 `exists()` 判断。

`kill()` 返回布尔：调用后实体是否已进入死亡状态。无敌实体、被事件取消的致死伤害会返回 `false`，不算错误。

## 权限

竞技场借用是排他的：一个竞技场同一时刻只有一个有效借据，也只登记一个借入者。不存在“同一竞技场被多方借用”。借入者可以是用户包，也可以是内置地图。

| 操作 | 校验 |
|------|------|
| 创建句柄、`find_entity`、`entity_count`、全部读取 | 借据有效、世界就绪；不校验修改权 |
| `arena.spawn`、非玩家实体的全部写操作（`remove` / `kill` / `teleport` / `set_*`） | 调用方是实体所在竞技场的当前借入者 |
| 非玩家实体的 `teleport_to` | 调用方同时是来源竞技场与目标竞技场的当前借入者 |
| 玩家句柄的 `teleport` / `teleport_to` | 目标玩家属于调用方有修改权的进行中比赛（含已淘汰者），即 `requirePlayer`；玩家当前所在世界是竞技场世界时，另需该竞技场的借入者校验；跨世界时目标竞技场同样要求借入者 |

玩家在大厅等非竞技场世界时，同世界 `teleport` 没有来源竞技场可校验，只校验玩家修改权；跨世界传送仍校验目标竞技场。

规则包不因“本场启用”获得竞技场资源的修改权。竞技场资源（方块、容器、实体）的规则不变：只有借入该竞技场的包可以修改。规则包修改玩家的权限由现有 `MutationAccess#requirePlayer` 决定，不受本设计影响。

## 生命周期

- 句柄不缓存实体实例；每次调用重新按 UUID 查找，避免区块卸载、跨维度、玩家重生留下的失效对象
- 非玩家实体在区块重载或跨世界传送时会被服务端替换对象，UUID 不变；但句柄按竞技场世界解析，实体离开该世界后原句柄失效，需要在目标竞技场重新获取
- 玩家重生会创建新的玩家对象，UUID 不变；玩家句柄按 UUID 重查，继续可用（玩家解析与所在世界无关）
- 区块卸载后实体仍存在于存档中，但句柄查不到；这不是删除
- 首版不提供转化操作；服务端自身发生的转化（如僵尸溺水）会让原句柄的类型断言失败，需要按新类型重新获取

## 事件

新增三个事件，只针对**竞技场世界内的非玩家实体**。玩家事件沿用 `player_*`。

| 事件名 | 回调签名 | 触发时机 |
|--------|----------|----------|
| `entity_spawn` | `function(uuid, type, match_or_nil)` | 非玩家实体在竞技场世界生成，包括脚本生成与自然生成 |
| `entity_death` | `function(uuid, type, killer_uuid_or_nil, match_or_nil)` | 非玩家活体在竞技场世界死亡；击杀者为实体时给出其 UUID，否则 `nil` |
| `entity_remove` | `function(uuid, type, cause, match_or_nil)` | 非玩家实体从竞技场世界移除 |

`entity_remove` 的 `cause` 为小写下划线字符串：`despawn`、`drop`、`enter_block`、`explode`、`hit`、`merge`、`out_of_world`、`pickup`、`plugin`、`transformation`、`discard`。

两条排除规则：

- 死亡不派发 `entity_remove`，由 `entity_death` 单独表示
- 区块卸载不派发 `entity_remove`；实体仍在存档中，重新加载后依旧存在

事件载荷只有值（UUID 字符串、实体键字符串、原因字符串）与末位的比赛句柄（无进行中的比赛时为 `nil`），不暴露实体对象，不支持取消或改写。比赛句柄按归属授予，本包不是本场归属方时只读。

事件不携带竞技场标识：订阅者可能收到其他竞技场世界的事件。脚本需要判断是否为本场实体时，用自己的竞技场句柄调 `find_entity(uuid, type)`；返回 `nil` 即不属于该竞技场。

## 落地顺序

1. `EntityDescriptor` 值类型与解析、校验
2. `EntityHandle` 非玩家根、句柄解析器、`PlayerHandle`
3. 玩家操作迁移到玩家句柄；竞技场句柄不再提供玩家操作函数；同步 `LuaAPI.md`
4. `LivingEntityHandle` / `MobHandle` / `MonsterHandle` / `AnimalHandle` / `ArmorStandHandle` / `ItemHandle` / `PaintingHandle`
5. `arena.spawn`、`arena.entity_count`、数量上限与区块处理
6. 实体事件桥接
7. 范围查询与按类型列出实体（另行设计）

## 实现约束

- `EntityHandle` 持有竞技场句柄、UUID、预期实体类型三项；解析实体时经竞技场句柄取世界，再按 UUID 查找
- `PlayerHandle` 不按世界查找，按 UUID 取在线玩家
- 句柄类在构建时按预期类型选定，构建后不再改变
- 句柄表在脚本持有期间有效；竞技场归还后，除 `uuid()` / `type()` 外的函数抛错（借据校验失败）
- `EntityDescriptor` 在接触世界前完成解析与能力校验
- 实体事件桥接按包注册，与玩家事件桥接同构，只观察不取消

## 待定

- 生成到未加载区块后立刻卸载，会让新实体在区块再次加载前不可访问。若希望“生成即可操作”，需要改成生成后保持区块加载；两种行为的取舍需要确认
- 实体数量查询的实际复杂度取决于服务端实现，若确认是线性遍历，文档需写明“不承诺常数时间”
- `kill()` 对无敌实体返回 `false` 的语义是否需要改成“抛错”
- 非玩家实体跨世界后原句柄失效，是否需要在句柄上提供“重新绑定到目标竞技场”的便利函数
