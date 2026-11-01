# DataSyncLib

**Minecraft 数据同步库** — 基于注解的自动数据同步与持久化框架。

## 简介

DataSyncLib 是为 Minecraft 模组开发打造的数据同步框架。通过声明式注解
（`@SyncToClient`、`@SyncToServer`、`@SaveToDisk`），自动完成客户端-服务端字段同步、
变更检测和磁盘持久化，大幅减少样板代码。

## 架构

```
注解层 → FieldDefinitionStorage → DataFieldDefinition[]
                                        ↓
IFieldDataHolder → LazyFieldDataManager → FieldDataManager → DataField[]
                     (DCL 懒加载)          (每实例)         (AbstractField / AbstractFieldAccess)
```

| 组件 | 职责 |
|------|------|
| **FieldDefinitionStorage** | 全局缓存 — 扫描类层级中的注解字段，生成 `DataFieldDefinition[]` |
| **FieldDataManager** | 每实例管理器 — 字段发现 → 变更检测 → 网络序列化 → 磁盘序列化 |
| **DataField 体系** | `AbstractField`（值类型：原始/对象）、`AbstractFieldAccess`（容器类型：集合/Map/数组） |
| **DataSyncCodec** | 统一编解码注册表，配对 `StreamCodec<B, T>`（网络）+ `ValueCodec`（持久化）；内置缓冲区编解码器集中在 `ByteBufCodecs` |
| **载体值类型（ValueOps）** | 19 种封闭二进制类型，比 NBT Tag 更紧凑，支持 VarInt 变长编码 |

## 核心特性

- **🔁 双向同步** — `@SyncToClient` 和 `@SyncToServer` 自动处理服务端↔客户端字段同步，异步安全
- **💾 自动持久化** — `@SaveToDisk` 字段自动写入/读取 NBT 数据
- **📡 增量同步** — 内置脏标记机制，通过索引寻址协议仅传输变更字段
- **🔧 可扩展编解码** — 统一 `DataSyncCodec` 注册表，预注册 30+ 类型，支持 `@Codec` 自定义
- **📢 变更通知** — 字段级监听器回调 + `NotifiableHolder` 响应式更新系统
- **📦 DataComponent 系统** — 基于标识（identity）的组件数据模型，内置合并语义
- **🗂️ Registry 工具** — 泛型注册表，支持 freeze/unfreeze 生命周期，内置 3 种序列化方式
- **⚡ 高性能** — MethodHandle 替代反射、FastUtil 集合、多级缓存、VarInt 紧凑编码
- **🗜️ 自定义数据类型** — 19 种载体值类型（ValueOps），模组自己的对象用 `ValueConverters` 转换器扩展（`CustomTypes` 是库内部 payload 的注册表，不对外）
- **🧩 开箱即用** — 继承 `FieldDataHolderBlockEntity` 即可获得全部能力
- **🪆 嵌套 Holder** — `@AdditionalHolder` 递归发现嵌套对象中的注解字段；`childManager` 模式为子对象生成独立子管理器
- **🧬 泛型层级解析** — `ReflectUtil` 沿父类/接口解析完整泛型实参（如 `A<T> extends HashMap<String,T>` 可取得 `[String, T]`）
- **♻️ Registry 全局 codec** — `Registry` 冻结时自动把流式+持久化 codec 注册进全局 `DataSyncCodec`
- **🎯 自定义策略** — `@Strategy` 为复杂类型自定义哈希/相等性变更检测
- **🧷 扩展注解** — 外部模组注册自己的字段注解，扫描时按注解类收集「注解 + 字段定义」配对
- **🧳 物品携带数据** — `@SaveToItem` 字段随方块掉落物走，放置时自动读回

## 快速开始

```java
public class MyBlockEntity extends FieldDataHolderBlockEntity {

    @SyncToClient
    @SaveToDisk
    private int energy = 0;

    @SyncToClient(scheduleUpdate = true)  // 值变更时触发客户端 scheduleUpdate
    private String status = "idle";

    @SyncToServer(autoDetect = false)   // 仅手动标记后才同步
    private int clientConfig = 0;

    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_BLOCK_ENTITY.get(), pos, state);
    }

    public void serverTick(ServerLevel level) {
        energy++;
        setChanged();  // 标记区块需要保存到磁盘
        DataSyncNetwork.syncBlockEntityToClient(this, false, true);  // 异步安全
    }

    // scheduleUpdate=true 的字段变更时，客户端会调用此方法
    @Override
    public void scheduleUpdate(LogicalSide side) {
        if (side.isClient()) {
            // 重新渲染或刷新 UI
        }
    }
}
```

### 高级用法：嵌套 Holder

```java
public class MachineBlockEntity extends FieldDataHolderBlockEntity {

    @AdditionalHolder  // 自动扫描 InventoryData 中的注解字段
    private InventoryData inventory = new InventoryData();

    @AdditionalHolder
    private EnergyData energy = new EnergyData();

    // 子管理器模式：子对象拥有独立的 FieldDataManager，不再扁平化到父管理器
    @SaveToDisk
    @SyncToClient
    @AdditionalHolder(childManager = true)
    private ModuleData module = new ModuleData();
}

class InventoryData {
    @SaveToDisk @SyncToClient
    private int itemCount;
}

class EnergyData {
    @SaveToDisk @SyncToClient(skipWhen = "skipEmptyEnergy")
    private long storedEnergy;

    // 跳过方法：返回 true 时不参与本轮同步（storedEnergy <= 0 不同步）
    private boolean skipEmptyEnergy(long value) {
        return value <= 0;
    }
}

class ModuleData { // 子管理器模式下的子对象（自身不需要实现 IFieldDataHolder）
    @SaveToDisk @SyncToClient
    private int ticks;
    @SaveToDisk
    private boolean enabled = true;
}
```

> **`@AdditionalHolder` 两种模式：**
> - **默认（扁平化）**：递归扫描子对象的注解字段，把它们当作直接声明在父管理器上处理，通过链式 getter 解析。
> - **`childManager = true`（子管理器）**：不再拍平。为子对象生成**独立 `FieldDataManager`**（内部用 `ChildFieldDataHolder` 包装），整个子对象作为一个字段，通过网络/磁盘统一编解码。子对象无需实现 `IFieldDataHolder`。可嵌套（子对象内再声明 `childManager` 会递归生成更下层管理器）。

### 高级用法：自定义 Codec

```java
// 1. 定义 Codec（利用 FieldDataManager 自动扫描 POJO 的注解字段）
private static final FieldDataCodec<MyConfig> CONFIG_CODEC =
    FieldDataManager.createCodec(MyConfig.class, MyConfig::new);

// 2. 在字段上引用
@SaveToDisk
@SyncToClient
@Codec(saveCodec = "CONFIG_CODEC", syncCodec = "CONFIG_CODEC")
private MyConfig config = new MyConfig();

// MyConfig 内部的 @SaveToDisk 字段会自动参与序列化
class MyConfig {
    @SaveToDisk
    private int maxSpeed;
    @SaveToDisk
    private String mode;
}
```

### 高级用法：Entity 同步

```java
public class MyEntity extends Entity implements IFieldDataHolder {

    private final LazyFieldDataManager fieldDataManager = new LazyFieldDataManager(this);

    @SyncToClient
    private int state = 0;

    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager.get();
    }

    @Override
    public void tick() {
        if (!level().isClientSide()) {
            state = calculateState();
            DataSyncNetwork.syncEntityToClient(this);
        }
    }
}
```

> **关键要点：**
> - `FieldDataHolderBlockEntity` 自己就会同步：它在服务端（客户端）的异步执行器上注册一个后台任务，每 `getMinSyncCycle()` 个周期尝试一次，最长 `getMaxSyncCycle()` 个周期一次。显式调用 `DataSyncNetwork.syncBlockEntityToClient()` 仍然支持（异步安全），也是普通 `IFieldDataHolder` 需要的方式
> - `autoDetect = false` 的字段需要手动调用 `markFieldsForSync()` 才会同步
> - 磁盘持久化需要手动调用 BlockEntity 的 `setChanged()`
> - `syncBlockEntityToClient(be, false, true)` 中：`false`=增量同步（仅变更字段），`true`=仅检查 `autoDetect=true` 的字段
> - 完整示例参考 `TestBlockEntity`

### 高级用法：Registry 全局 codec 自动注册

`Registry` 是泛型注册表，支持按 key 排序分配稳定整数 id（网络流）与按 key 串化（磁盘）。当构造时传入**值的运行时类型**，`freeze()` 会自动把该注册表的 `streamCodec()` + `valueCodec()` 注册进全局 `DataSyncCodec`，无需手动调用。

```java
Registry<String, ResearchTag> TAGS = new Registry<>(
        "gtocore:research_tag", ValueCodec.STRING,  // key codec（按 key 编解码）
        t -> t.name,                                     // keyGetter：从值取回 key
        ResearchTag.class);                              // 值的运行时类型（用于全局注册）
TAGS.unfreeze();
TAGS.register("material", MATERIAL);
TAGS.freeze(); // ← 自动：DataSyncCodec.get(ResearchTag.class) 现在可用
```

注册后，任何 `ResearchTag` 类型的字段都能用 `DataSyncCodec.get(ResearchTag.class)` 自动编解码，而无需手动注册。

### 高级用法：自定义类型的 SELF 转换器

内置类型集覆盖不到的类型到达载体时会变成 `SELF` 值，默认走 Java 序列化。注册一个转换器后，它就以普通载体值的形式存储 —— 更小、在存档里可读、类结构变动也不会失效：

```java
ValueConverters.Converter.<UUID>builder(ValueOps.Type.LONG_ARRAY, UUID.class)
        .write((ops, uuid) -> ops.createUUID(uuid))        // payload 用 ops 的原语构造
        .read((ops, data) -> ops.getUUID(data))
        .build();                                          // build() 会完成注册
```

`builder` 的 id 是 payload 的 `ValueOps.Type` id，不是转换器自己的编号，也不会额外写进数据里：转换过的值就按它的 payload 存储，所以无论是字段编解码器写出的 UUID 还是作为不透明值写出的 UUID，存档里都是 `LONG_ARRAY`。payload 类型相同的转换器共用同一个 id，而同一个类只能注册一次。

如果转换器要代表一整族类型，加上选择器（谓词）。谓词按注册顺序匹配，先注册的优先，所以窄的放前面——一个注册覆盖全部 `IntList`、`IntSet` 之类就是这么做的：

```java
ValueConverters.Converter.<Tagged>builder(ValueOps.Type.STRING, Tagged.class)
        .when(Tagged.class::isAssignableFrom)              // 覆盖所有实现类
        .write((ops, tagged) -> ops.createString(tagged.tag()))
        .read((ops, data) -> Tagged.of(ops.getString(data)))
        .build();
```

读取侧是 `ValueOps#getSelf(data, Type.class)`：本来就是该类型就直接返回，是转换过的就用对应转换器还原，两者都不成立时报错而不是瞎猜。`JavaValueOps#init()` 在模组构造期已经替你注册了内置的那一批：

- `UUID` 存成 `LONG_ARRAY`，`BigInteger`/`BigDecimal` 存成 `BYTE_ARRAY`，以及类型集里没有自己 id 的数组（`boolean[]`→`BYTE_ARRAY`、`short[]`/`char[]`/`float[]`→`INT_ARRAY`、`double[]`→`LONG_ARRAY`），每个存的就是对应 `ValueOps#createXxx` 产出的 payload。
- **FastUtil 原始集合**：`IntList`/`LongList` 存成原始数组（和它们字段 codec 写的形状一致，值不会变成装箱数字的列表），`IntSet`/`LongSet` 同样存数组。**有序集合**故意不转换、继续走 Java 序列化：payload 里没有顺序，重建出来会是一个"相等判断方式不同"的集合。
- 枚举是**字段**类型（`DataSyncCodec` 按 ordinal 或名字存）；作为裸**值**它没有转换器——要放枚举就放进字段，或放进元素类型就是该枚举的集合里。

> **`CustomTypes` 是库自己的注册表，不是给模组用的。** 它的 key 是裸 `int`，落在存档和网络都会携带的同一张全局表里：16 以下已经被占（NBT `0…2`、JSON `3…5`），没有任何区间留给别人，撞 id 是直接报错而不是合并，而且 id 一旦发出去就永远收不回来。payload 格式还要求对端也得认识——没有该类型的客户端会直接拒绝这个值而不是降级——所以在这里注册的格式会渗进整个游戏的线格式。模组自己的对象是**值**，应该走上面的 `ValueConverters`：不占 id、不新增格式、也不会和谁冲突。读取自定义值（`ops.isCustom`、`ops.getCustom`）对所有代码开放，因为那是库自己的 payload 在回来。

### 高级用法：文件与文件版本号

`FileUtil` 按需要的那一半读写载荷——`StreamDecoder`/`StreamEncoder`（网络编解码器）或
`ValueDecoder`/`ValueEncoder`（载体编解码器），载荷在往返文件的过程中不会被复制。如果一个载荷的读取
依赖它写入时的版本，就把版本**写进文件本身**：

```java
FileUtil.writeVersioned(path, DATA_VERSION, codec, value);   // 文件开头写 VarInt 版本号，随后是载荷
var value = FileUtil.readVersioned(path, codec);             // 版本随载荷一起读回来
```

* 写入方显式传入 `int` 版本号；读取方不传，因为版本号是从文件开头读出来的。
* 流编解码器拿到的是 `VersionedFriendlyByteBuf`（带版本号的 `FriendlyByteBuf`），用 `buf.version()`
  取；载体编解码器从自己的 `ValueOps` 取，即 `ops.dataVersion()`。
* 版本号以 VarInt 写入，取值必须在 `1 .. FileUtil.MAX_VERSION`。`0` 不是本库会写出的版本号，因此
  **早于版本号机制**、载荷直接顶在文件开头的旧文件会被拒绝——这正是回退可行的原因：

```java
T value;
try {
    value = FileUtil.readVersioned(path, codec);
} catch (RuntimeException e) {
    value = FileUtil.read(path, legacyCodec);   // 文件带版本号之前写下的
}
```

迁移一个已有文件格式就该这么做：从此写带版本号的，把旧读取放进 catch 里，等没有文件再需要它时删掉旧
编解码器。不是本库自己字节的文件——随包资源、给人看的导出文本、可丢弃的缓存——不加版本号。

### 高级用法：泛型层级解析

`ReflectUtil` 提供了沿泛型**父类/接口/多层继承**解析完整泛型实参的工具，适合需要从"实现了泛型接口/继承了泛型父类"的字段类型反推完整类型参数的场景。

```java
// class A<T> extends HashMap<String, T>
// 字段：A<Integer> value;
Type fieldType = A.class.getDeclaredField("value").getGenericType(); // A<Integer>
Class<?>[] args = ReflectUtil.getResolvedGenericArguments(fieldType, HashMap.class);
// → [String.class, Integer.class]  （HashMap 的 2 个参数：String 固定 + Integer 由 T 替换）
```

支持父类、泛型接口（`List<T>`）、多层继承、泛型接口等任意祖先形态。

### 高级用法：扩展注解

外部模组可以把自己的字段注解交给库收集。注解注册之后，它就和 `@AddToManager` 一样成为字段被管理的原因；扫描类时，库会把每个带该注解的字段按注解类存起来 —— 字段上的注解实例，配对该字段的 `DataFieldDefinition`。可以再给注册传一个可选的 metadata 工厂，把注解属性的解析结果一并存在该字段的记录里。

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DirtyTracked {
    String channel() default "default";
}

// 模组构造期，和工厂注册放在一起
FieldDefinitionStorage.registerAnnotation(DirtyTracked.class,
        (lookup, definition, type, annotation) -> annotation.channel());   // 可选：自定义 metadata

public class MyBlockEntity extends FieldDataHolderBlockEntity {

    @SaveToDisk
    @DirtyTracked(channel = "energy")
    private int energy;

    @DirtyTracked(channel = "status")   // 只靠自定义注解也会被管理
    private int status;
}
```

读回来：

```java
var storage = FieldDefinitionStorage.get(MyBlockEntity.class);
for (var annotated : storage.getAnnotatedFields(DirtyTracked.class)) {
    var channel = annotated.annotation().channel();   // 注解实例
    var parsed = (String) annotated.metadata();       // 工厂产出，扫描期算一次
    var definition = annotated.definition();          // 该字段的定义，可直接读写
    definition.get(blockEntity);
}
```

* `FieldDefinitionStorage.AnnotatedField` 是 `(definition, annotation, metadata)` 三元组。一个注解可以标多个字段，一个字段也可以带多个已注册的注解。
* metadata 工厂签名 `(lookup, definition, managedType, annotation) -> Object`：`lookup` 是字段声明类的私有访问 `Lookup`（可直接配 `ReflectUtil.createAdaptedMethodHandle` 建方法句柄），`definition` 是该字段的 `DataFieldDefinition`（`definition.field` 就是反射字段），`managedType` 是字段的管理类型（`@Conversion` 时是 `toManaged` 类型），返回值随便是什么都行，扫描时算一次就固定下来；不传工厂或返回 `null` 时 `metadata()` 为 `null`。
* `getAnnotatedFields(...)` 直接返回类存储持有的数组 —— 不复制、只读，查询本身不构造新对象；没有匹配字段时返回共享的空数组。
* 注册时会校验注解：必须是 `@Retention(RetentionPolicy.RUNTIME)` 且 `@Target(ElementType.FIELD)`，否则抛 `IllegalArgumentException`。重复注册是空操作。
* 注册必须发生在目标类第一次被扫描之前（模组构造期）：类的存储是永久缓存的，后注册的注解不会回溯生效。
* 只靠自定义注解被管理的字段只参与 `writeAllToValue`/`readAllFromValue` 这类全量路径 —— 不会自动同步，也不进增量存档，和 `@AddToManager` 完全一致。

### 高级用法：随物品保存（`@SaveToItem`）

方块实体被破坏时，掉落物默认不带方块数据。给字段打上 `@SaveToItem`，这些字段就随掉落物走，重新放置时读回来（实现方式对齐 GregTech Modern 的 `IDropSave`）：

```java
public class MyMachineBlockEntity extends FieldDataHolderBlockEntity {

    @SaveToItem
    private int mode;          // 破坏时写进物品，放置时恢复

    @SaveToItem(skipWhen = "isUpgradeInactive", saveOnClone = false)
    private int upgradeMode;   // 升级未生效就不进物品；创造模式克隆的物品不带

    private boolean isUpgradeInactive(int mode) {
        return mode < 0;
    }

    @SaveToDisk
    private int energy;        // 只进世界存档，不随物品走
}

// 方块侧：三个钩子，基类全做了
public class MyMachineBlock extends FieldDataHolderBlock {
    public MyMachineBlock(Properties properties) { super(properties); }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MyMachineBlockEntity(pos, state);
    }
}
```

* 只有被标记的字段进物品。`@SaveToItem` 自己就会管理字段（等价 `@AddToManager`），所以不必同时写 `@SaveToDisk`；两者都写时两个地方都存。
* 物品载荷放在物品**根 tag** 的 `field_item`（字节数组）和 `field_item_dataVersion` 里，格式和存档载荷一致；物品仍是普通方块物品，不碰 `BlockEntityTag`。
* 两个注解属性：
  - `skipWhen = "方法名"`：每次写入物品前用字段当前值判断，返回 `true` 就不把这个字段写进物品。规则和 `@SaveToDisk(skipWhen = ...)` 相同：签名 `(T) -> boolean`，`T` 是字段的管理类型（`@Conversion` 时是 `toManaged` 后的类型），方法声明在字段所在类上。
  - `saveOnClone = false`：只在真实掉落时携带；创造模式中键取方块的克隆物品不带它（属性不能叫 `clone`，与 `Object#clone` 重名）。
* 方块实体实现 `IDropSave`（`FieldDataHolderBlockEntity` 已实现）：
  - `getDrops` → `saveToItem(tag, false)`，只写本方块自己的那一个掉落物，战利品表结果本身不改；
  - `setPlacedBy` → `loadFromItem(tag)`，放置时读回；
  - `getCloneItemStack` → `saveToItem(tag, true)`，创造模式中键取方块也带上支持克隆的字段。
* 继承 `FieldDataHolderBlock` 就自动有这三个钩子；方块已有自己的基类时，直接调用 `IDropSave` 的静态工具方法即可：`IDropSave.getDrops(this, super.getDrops(state, params), params)`、`IDropSave.setPlacedBy(level, pos, stack)`、`IDropSave.getCloneItemStack(stack, level, pos)`，方块实体侧完全不变。
* 方块基类在方块实体什么都没写时会把空 tag 清掉，所以普通掉落物不会有空 NBT；一个掉落物只写一次，不会复制多份状态。

## 注解速查表

| 注解 | 作用 | 常用属性 |
|------|------|---------|
| `@SyncToClient` | 服务端→客户端同步 | `autoDetect`（默认 true）、`scheduleUpdate`、`skipWhen`、`listener` |
| `@SyncToServer` | 客户端→服务端同步 | `autoDetect`（默认 true）、`scheduleUpdate`、`skipWhen`、`listener` |
| `@SaveToDisk` | 磁盘持久化 | `key`（自定义键名）、`skipWhen`、`saveEmpty`、`defaultValue`、`listener`（磁盘加载回调） |
| `@Access` | 强制使用访问模式（容器类） | `instanceAsValue` |
| `@AdditionalHolder` | 递归扫描嵌套对象字段，或为子对象生成独立子管理器 | `childManager`（true=子管理器模式） |
| `@Codec` | 自定义序列化方式 | `saveCodec` / `syncCodec` / `writeToValue` 等 |
| `@Conversion` | 以另一种类型存储/同步字段（静态 `Function`） | `toManaged`（必填）、`toField`（可选） |
| `@Strategy` | 自定义变更检测策略 | `value`（static 字段名） |
| `@Generic` | 强制使用泛型工厂链 | — |
| `@AddToManager` | 加入管理但不自动同步/持久化 | — |
| `@SaveToItem` | 字段随方块掉落物保存，放置时恢复 | — |

外部模组自己定义的注解用 `FieldDefinitionStorage.registerAnnotation(...)` 注册，再用
`getAnnotatedFields(...)` 读回来 —— 见[高级用法：扩展注解](#高级用法扩展注解)。

## 数据流

### 同步流程（服务端→客户端）

```
服务端 tick()
  ├── 修改字段值
  ├── DataSyncNetwork.syncBlockEntityToClient(be, false, true)
  │     ├── FieldDataManager.updateFieldDirtyFlags(SERVER, auto)
  │     │     └── 遍历 syncToClientFields → detectChange()
  │     │           比较当前值与上次快照 → 标记 changed
  │     ├── FieldDataManager.writeToNetworkBuffer(SERVER, writeAll)
  │     │     ├── writeCustomSyncData()         → 自定义数据
  │     │     └── 遍历: writeVarInt(索引) + 字段值 → byte[]
  │     └── CHANNEL.send(TRACKING_CHUNK, packet)
  │
客户端接收
  ├── handleBlockEntity()       （单一处理器，方向由 getOriginationSide() 判定）
        ├── applyBlockEntitySyncData(level, pos, data, CLIENT)
        │     └── FieldDataManager.readFromNetworkBuffer(CLIENT, data)
        │           ├── readCustomSyncData()
        │           ├── while buf: readVarInt(索引) → field.readFromBuffer()
        │           └── scheduleUpdate=true → scheduleUpdate()
```

### 持久化流程

```
BlockEntity.saveAdditional(tag)
  └── tag.putByteArray("field_save", JavaValueOps.INSTANCE.toBytes(writeToValue(JavaValueOps.INSTANCE)))
        └── FieldDataManager.writeToValue()
              ├── writeCustomSaveData()
              └── 遍历 saveFields → field.writeToValue()
                    └── 生成 carrier string map (key → value)

BlockEntity.load(tag)
  ├── 优先检查 "field_sync"（区块加载同步数据）
  └── 否则读取 "field_save"（磁盘持久化数据）
        └── FieldDataManager.readFromValue(data, JavaValueOps.create(VERSION))
              ├── readCustomSaveData()
              └── 遍历 saveFields → field.readFromValue()
```

## 文档

### 📖 完整文档（在浏览器中打开）

详细的架构说明和 API 参考以独立 HTML 页面提供（请直接在浏览器中打开，而非通过 IDE 文件查看器）：

| 语言 | 文件 | 内容 |
|------|------|------|
| 中文 | **[docs/index.html](docs/index.html)** | 架构设计、数据流、接口参考、注解参考、使用指南 |
| English | **[docs/index_en.html](docs/index_en.html)** | Architecture, data flow, API reference, usage guide |

> **如何打开：** 在 IDE 中右键文件选择"在浏览器中打开"，或在文件管理器中直接双击。
> 这些是带侧边栏导航的样式化 HTML 页面，不适合以源码方式查看。

### 🌐 其他语言
- **[README.md](README.md)** — English README

## 项目依赖

| 组件 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.21 |
| Java | 21 |
| FastUtil | (Forge 内置) |
| Lombok | (编译期) |

## 许可证

GNU LGPL 3.0
