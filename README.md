# DataSyncLib

**Minecraft 数据同步库** — 基于注解的自动化数据同步与持久化框架。

## 简介

DataSyncLib 是专为 Minecraft 模组开发设计的数据同步框架。通过声明式注解
（`@SyncToClient`、`@SyncToServer`、`@SaveToDisk`），自动处理客户端与服务端之间的
字段同步、变更检测与磁盘持久化，大幅减少样板代码。

## 核心特性

- **🔁 双向同步** — `@SyncToClient` 和 `@SyncToServer` 自动处理服务端↔客户端字段同步，支持异步调用
- **💾 自动持久化** — `@SaveToDisk` 自动将字段写入/读取 NBT 数据
- **📡 增量同步** — 内置 dirty flag 机制，仅传输变化的字段
- **🔧 可扩展 Codec** — 统一的 `DataSyncCodec` 注册表，预置 30+ 常用类型
- **📢 变更通知** — Listener 回调 + NotifiableHolder 系统
- **📦 DataComponent 系统** — 组件化数据模型，`DataComponentRegistry` + `DataComponentMap`
- **🗂️ Registry 工具** — 泛型注册表，支持 freeze/unfreeze 生命周期和内置序列化
- **🧩 开箱即用** — 继承 `FieldDataHolderBlockEntity` 即可获得完整能力

## 快速开始

```java
public class MyBlockEntity extends FieldDataHolderBlockEntity {

    @SyncToClient
    @SaveToDisk
    private int energy = 0;

    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_BLOCK_ENTITY.get(), pos, state);
    }

    public void serverTick(ServerLevel level) {
        energy++;
        setChanged();  // 标记需要保存到磁盘
        DataSyncNetwork.syncBlockEntityToClient(this, false, true);  // 异步安全
    }
}
```

> **关键：** 同步需主动调用 `DataSyncNetwork.syncBlockEntityToClient()`（支持异步），
> `markFieldsForSync` 用于 `autoUpdate = false` 的字段。
> 持久化需手动调用 `setChanged()`。
> 完整示例参考 `TestBlockEntity` 类。

## 文档

👉 **[完整文档 (HTML)](docs/index.html)**

文档涵盖：架构设计、同步与持久化流程、数据流、所有核心接口详解、注解完整参考、
DataComponent 系统、Registry 工具类、使用指南（BlockEntity / Entity / 普通类 / 嵌套 Holder）、
高级特性、完整 API 参考。

## 技术栈

| 组件 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.21 |
| Java | 21 |

## 许可证

GNU LGPL 3.0
