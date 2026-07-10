# DataSyncLib

**Minecraft Data Synchronization Library** — Annotation-driven automatic data sync and persistence framework.

## Introduction

DataSyncLib is a data synchronization framework built for Minecraft Forge mod development.
Using declarative annotations (`@SyncToClient`, `@SyncToServer`, `@SaveToDisk`), it automatically
handles client-server field synchronization, change detection, and disk persistence — drastically
reducing boilerplate code.

## Key Features

- **🔁 Bidirectional Sync** — `@SyncToClient` and `@SyncToServer` handle server↔client field sync automatically. Async-safe.
- **💾 Auto Persistence** — `@SaveToDisk` fields are automatically written to/read from NBT data.
- **📡 Incremental Sync** — Built-in dirty flag mechanism transmits only changed fields.
- **🔧 Extensible Codec** — Unified `DataSyncCodec` registry with 30+ pre-registered types.
- **📢 Change Notification** — Listener callbacks + NotifiableHolder system.
- **📦 DataComponent System** — Component-based data model via `DataComponentRegistry` + `DataComponentMap`.
- **🗂️ Registry Utility** — Generic registry with freeze/unfreeze lifecycle and built-in serialization.
- **⚡ High Performance** — MethodHandle instead of reflection, FastUtil collections, multi-level caching, VarInt compact encoding.
- **🗜️ Data Type System** — Custom 19-type binary Data system, more compact and efficient than Tag.
- **🧩 Ready to Use** — Extend `FieldDataHolderBlockEntity` to get full capabilities out of the box.

## Quick Start

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
        setChanged();  // Mark chunk for saving to disk
        DataSyncNetwork.syncBlockEntityToClient(this, false, true);  // Async-safe
    }
}
```

> **Key points:** Sync requires explicit calls to `DataSyncNetwork.syncBlockEntityToClient()` (async-safe).
> `markFieldsForSync` is for `autoUpdate = false` fields.
> Persistence requires manual `setChanged()` calls.
> See `TestBlockEntity` for a complete example.

## Documentation

👉 **[Full Documentation - English (HTML)](docs/index_en.html)** | 👉 **[完整文档 - 中文 (HTML)](docs/index.html)**

Covers: architecture design, sync & persistence flow, data flow, all core interfaces, complete annotation reference,
DataComponent system, Registry utility, performance design (MethodHandle / FastUtil / Data type system / multi-level caching),
usage guide (BlockEntity / Entity / plain class / nested holders), advanced features, full API reference.

## Tech Stack

| Component | Version |
|-----------|---------|
| Minecraft | 1.20.1 |
| Forge    | 47.4.21 |
| Java     | 21      |

## License

GNU LGPL 3.0
