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
- **🪆 嵌套 Holder** — `@AdditionalHolder` 递归发现嵌套对象中的注解字段
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
```

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

## 注解速查表

| 注解 | 作用 | 常用属性 |
|------|------|---------|
| `@SyncToClient` | 服务端→客户端同步 | `autoUpdate`（默认 true）、`notifyUpdate`、`condition`、`listener` |
| `@SyncToServer` | 客户端→服务端同步 | `autoUpdate`（默认 true）、`notifyUpdate`、`condition`、`listener` |
| `@SaveToDisk` | 磁盘持久化 | `key`（自定义键名）、`condition`、`saveNull`、`defaultValue` |
| `@Access` | 强制使用访问模式（容器类） | `createInstance` |
| `@AdditionalHolder` | 递归扫描嵌套对象字段 | — |
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
