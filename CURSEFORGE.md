# DataSyncLib

**Annotation-driven automatic data sync and persistence framework for Minecraft mod development.**

## 📖 Overview

**DataSyncLib** is a powerful data synchronization library designed for Minecraft mod developers. It eliminates the boilerplate of manual network packet handling and NBT persistence by using declarative Java annotations.

Simply annotate your fields with `@SyncToClient`, `@SyncToServer`, or `@SaveToDisk`, and DataSyncLib automatically handles:

- 🔁 **Client-server field synchronization** — async-safe, incremental delta sync
- 💾 **Disk persistence** — automatic NBT save/load with change detection
- 📡 **Network transmission** — index-addressed protocol, only changed fields are sent
- 🔧 **Custom codecs** — 30+ pre-registered types, extensible via `@Codec`

---

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| **🔁 Bidirectional Sync** | `@SyncToClient` and `@SyncToServer` handle server↔client field sync. Fully async-safe. |
| **💾 Auto Persistence** | `@SaveToDisk` fields are automatically written to and read from NBT. |
| **📡 Incremental Sync** | Built-in dirty flag detection — only changed fields are transmitted over the network. |
| **🔧 Extensible Codec System** | `DataSyncCodec` registry with 30+ pre-registered types. Register custom codecs via `@Codec`. |
| **📢 Change Notification** | Per-field listener callbacks + `NotifiableHolder` system for reactive UI updates. |
| **📦 DataComponent System** | Identity-keyed component data model with `DataComponentRegistry` + `DataComponentMap`. |
| **🗂️ Registry Utility** | Generic registry with freeze/unfreeze lifecycle and built-in serialization. |
| **⚡ High Performance** | `MethodHandle` instead of reflection, `FastUtil` collections, multi-level caching, `VarInt` encoding. |
| **🗜️ Compact Data Format** | Custom 19-type binary Data system, more compact than NBT Tag. |
| **🧩 Ready to Use** | Extend `FieldDataHolderBlockEntity` — full capabilities out of the box. |
| **🪆 Nested Holders** | `@AdditionalHolder` recursively discovers annotated fields in nested objects. |
| **🎯 Custom Strategies** | `@Strategy` for custom hash/equality change detection on complex types (ItemStack, FluidStack, etc.). |

---

## 🚀 Quick Start

```java
public class MyBlockEntity extends FieldDataHolderBlockEntity {

    @SyncToClient
    @SaveToDisk
    private int energy = 0;

    @SyncToClient(notifyUpdate = true)  // Triggers scheduleUpdate on client
    private String status = "idle";

    @SyncToServer(autoUpdate = false)   // Only synced when explicitly marked
    private int clientConfig = 0;

    public MyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_BLOCK_ENTITY.get(), pos, state);
    }

    public void serverTick(ServerLevel level) {
        energy++;
        setChanged();  // Mark chunk for saving
        DataSyncNetwork.syncBlockEntityToClient(this, false, true);  // Async-safe
    }

    @Override
    public void scheduleUpdate(LogicalSide side) {
        if (side.isClient()) {
            // Re-render or refresh UI on client
        }
    }
}
```

### Nested Holders

```java
public class MachineBlockEntity extends FieldDataHolderBlockEntity {

    @AdditionalHolder  // Scans InventoryData for annotated fields
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

    private boolean shouldSyncEnergy(long value) {
        return value > 0;  // Skip sync when empty
    }
}
```

### Custom Codec

```java
// 1. Define codec — FieldDataManager auto-discovers @SaveToDisk fields on the POJO
private static final FieldDataCodec<MyConfig> CONFIG_CODEC =
    FieldDataManager.createCodec(MyConfig.class, MyConfig::new);

// 2. Reference on the field
@SaveToDisk @SyncToClient
@Codec(saveCodec = "CONFIG_CODEC", syncCodec = "CONFIG_CODEC")
private MyConfig config = new MyConfig();
```

### Entity Sync

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

---

## 📋 Annotation Reference

| Annotation | Purpose | Key Attributes |
|------------|---------|---------------|
| `@SyncToClient` | Server→Client sync | `autoUpdate`, `notifyUpdate`, `condition`, `listener` |
| `@SyncToServer` | Client→Server sync | `autoUpdate`, `notifyUpdate`, `condition`, `listener` |
| `@SaveToDisk` | Disk persistence | `key`, `condition`, `saveNull`, `defaultValue`, `defaultValueGetter` |
| `@Access` | Force access-mode for containers | `createInstance` |
| `@AdditionalHolder` | Recursively scan nested object fields | — |
| `@Codec` | Custom serialization | `saveCodec` / `syncCodec` / `writeToData` / `readFromData` |
| `@Strategy` | Custom change detection strategy | `value` (static field name) |
| `@Generic` | Force generic-type factory resolution | — |
| `@AddToManager` | Add to manager without auto sync/persist | — |

---

## 📦 Installation

### Prerequisites
- **Java** 21

### For Developers
```gradle
repositories {
    maven {
        url = "https://maven.gtodyssey.com/releases"
    }
}

dependencies {
    implementation fg.deobf("com.gto:datasynclib-forge-1.20.1:26.7.4")
}
```

---

## 📖 Full Documentation

Detailed documentation with architecture diagrams, data flow charts, API reference, and advanced usage guides:

- **[English Documentation](docs/index_en.html)** — Open in browser for best experience
- **[中文文档](docs/index.html)** — 中文完整文档

---

## 🏗️ Architecture

```
Annotations → FieldDefinitionStorage → DataFieldDefinition[]
                                            ↓
IFieldDataHolder → LazyFieldDataManager → FieldDataManager → DataField[]
                    (DCL lazy init)          (per-instance)     (AbstractField / AbstractFieldAccess)
```

| Component | Role |
|-----------|------|
| **FieldDefinitionStorage** | Global cache — scans class hierarchy for annotated fields |
| **FieldDataManager** | Per-instance lifecycle manager — field discovery, change detection, serialization |
| **DataField hierarchy** | `AbstractField` (primitive values), `ObjField` (objects with codecs), `AbstractFieldAccess` (collections/maps/arrays) |
| **DataSyncCodec** | Unified codec registry pairing `ByteStreamCodec` (network) with `DataCodec` (persistence) |
| **Data type system** | 19-type sealed binary format, more compact than NBT, with VarInt encoding |

---

## 💡 Inspiration

This project draws inspiration from **[LDLib's syncdata package](https://github.com/Low-Drag-MC/LDLib-MultiLoader/tree/1.20.1/common/src/main/java/com/lowdragmc/lowdraglib/syncdata)** by Low-Drag-MC, a powerful multi-loader library for Minecraft mod development.

---

## 📄 License

This project is licensed under the **GNU LGPL 3.0** — you are free to use, modify, and distribute this library in your own mods.
