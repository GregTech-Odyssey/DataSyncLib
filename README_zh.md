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
| **DataSyncCodec** | 统一编解码注册表，配对 `ByteStreamCodec`（网络）+ `DataCodec`（持久化） |
| **Data 类型系统** | 19 种密封二进制类型，比 NBT Tag 更紧凑，支持 VarInt 变长编码 |

## 核心特性

- **🔁 双向同步** — `@SyncToClient` 和 `@SyncToServer` 自动处理服务端↔客户端字段同步，异步安全
- **💾 自动持久化** — `@SaveToDisk` 字段自动写入/读取 NBT 数据
- **📡 增量同步** — 内置脏标记机制，通过索引寻址协议仅传输变更字段
- **🔧 可扩展编解码** — 统一 `DataSyncCodec` 注册表，预注册 30+ 类型，支持 `@Codec` 自定义
- **📢 变更通知** — 字段级监听器回调 + `NotifiableHolder` 响应式更新系统
- **📦 DataComponent 系统** — 基于标识（identity）的组件数据模型，内置合并语义
- **🗂️ Registry 工具** — 泛型注册表，支持 freeze/unfreeze 生命周期，内置 3 种序列化方式
- **⚡ 高性能** — MethodHandle 替代反射、FastUtil 集合、多级缓存、VarInt 紧凑编码
- **🗜️ 自定义数据类型** — 19 种二进制 Data 类型系统，支持 CustomData 扩展
- **🧩 开箱即用** — 继承 `FieldDataHolderBlockEntity` 即可获得全部能力
- **🪆 嵌套 Holder** — `@AdditionalHolder` 递归发现嵌套对象中的注解字段；`childManager` 模式为子对象生成独立子管理器
- **🧬 泛型层级解析** — `ReflectUtil` 沿父类/接口解析完整泛型实参（如 `A<T> extends HashMap<String,T>` 可取得 `[String, T]`）
- **♻️ Registry 全局 codec** — `Registry` 冻结时自动把流式+持久化 codec 注册进全局 `DataSyncCodec`
- **🎯 自定义策略** — `@Strategy` 为复杂类型自定义哈希/相等性变更检测

## 快速开始

```java
public class MyBlockEntity extends FieldDataHolderBlockEntity {

    @SyncToClient
    @SaveToDisk
    private int energy = 0;

    @SyncToClient(notifyUpdate = true)  // 值变更时触发客户端 scheduleUpdate
    private String status = "idle";

    @SyncToServer(autoUpdate = false)   // 仅手动标记后才同步
    private int clientConfig = 0;

    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_BLOCK_ENTITY.get(), pos, state);
    }

    public void serverTick(ServerLevel level) {
        energy++;
        setChanged();  // 标记区块需要保存到磁盘
        DataSyncNetwork.syncBlockEntityToClient(this, false, true);  // 异步安全
    }

    // notifyUpdate=true 的字段变更时，客户端会调用此方法
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
    @SaveToDisk @SyncToClient(condition = "shouldSyncEnergy")
    private long storedEnergy;

    // 条件方法：返回 true 时跳过同步（storedEnergy <= 0 不同步）
    private boolean shouldSyncEnergy(long value) {
        return value > 0;
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
> - 同步需要显式调用 `DataSyncNetwork.syncBlockEntityToClient()`（异步安全，可跨线程调用）
> - `autoUpdate = false` 的字段需要手动调用 `markFieldsForSync()` 才会同步
> - 磁盘持久化需要手动调用 BlockEntity 的 `setChanged()`
> - `syncBlockEntityToClient(be, false, true)` 中：`false`=增量同步（仅变更字段），`true`=仅检查 `autoUpdate=true` 的字段
> - 完整示例参考 `TestBlockEntity`

### 高级用法：Registry 全局 codec 自动注册

`Registry` 是泛型注册表，支持按 key 排序分配稳定整数 id（网络流）与按 key 串化（磁盘）。当构造时传入**值的运行时类型**，`freeze()` 会自动把该注册表的 `streamCodec()` + `dataCodec()` 注册进全局 `DataSyncCodec`，无需手动调用。

```java
Registry<String, ResearchTag> TAGS = new Registry<>(
        "gtocore:research_tag", DataCodec.STRING_CODEC,  // key codec（按 key 编解码）
        t -> t.name,                                     // keyGetter：从值取回 key
        ResearchTag.class);                              // 值的运行时类型（用于全局注册）
TAGS.unfreeze();
TAGS.register("material", MATERIAL);
TAGS.freeze(); // ← 自动：DataSyncCodec.get(ResearchTag.class) 现在可用
```

注册后，任何 `ResearchTag` 类型的字段都能用 `DataSyncCodec.get(ResearchTag.class)` 自动编解码，而无需手动注册。

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

## 注解速查表

| 注解 | 作用 | 常用属性 |
|------|------|---------|
| `@SyncToClient` | 服务端→客户端同步 | `autoUpdate`（默认 true）、`notifyUpdate`、`condition`、`listener` |
| `@SyncToServer` | 客户端→服务端同步 | `autoUpdate`（默认 true）、`notifyUpdate`、`condition`、`listener` |
| `@SaveToDisk` | 磁盘持久化 | `key`（自定义键名）、`condition`、`saveNull`、`defaultValue` |
| `@Access` | 强制使用访问模式（容器类） | `createInstance` |
| `@AdditionalHolder` | 递归扫描嵌套对象字段，或为子对象生成独立子管理器 | `childManager`（true=子管理器模式） |
| `@Codec` | 自定义序列化方式 | `saveCodec` / `syncCodec` / `writeToData` 等 |
| `@Strategy` | 自定义变更检测策略 | `value`（static 字段名） |
| `@Generic` | 强制使用泛型工厂链 | — |
| `@AddToManager` | 加入管理但不自动同步/持久化 | — |

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
  ├── handleBlockEntityS2C()
        ├── applyBlockEntitySyncData()
        │     └── FieldDataManager.readFromNetworkBuffer(CLIENT, data)
        │           ├── readCustomSyncData()
        │           ├── while buf: readVarInt(索引) → field.readFromBuffer()
        │           └── notifyUpdate → scheduleUpdate()
```

### 持久化流程

```
BlockEntity.saveAdditional(tag)
  └── tag.putByteArray("field_save", writeToData().writeToBytes())
        └── FieldDataManager.writeToData()
              ├── writeCustomSaveData()
              └── 遍历 saveFields → field.writeToData()
                    └── 生成 StringMapData(key → Data)

BlockEntity.load(tag)
  ├── 优先检查 "field_sync"（区块加载同步数据）
  └── 否则读取 "field_save"（磁盘持久化数据）
        └── FieldDataManager.readFromData(data, VERSION)
              ├── readCustomSaveData()
              └── 遍历 saveFields → field.readFromData()
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
