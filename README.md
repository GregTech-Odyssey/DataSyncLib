# DataSyncLib

**Minecraft Data Synchronization Library** — Annotation-driven automatic data sync and persistence framework.

## Introduction

DataSyncLib is a data synchronization framework built for Minecraft mod development.
Using declarative annotations (`@SyncToClient`, `@SyncToServer`, `@SaveToDisk`), it automatically
handles client-server field synchronization, change detection, and disk persistence — drastically
reducing boilerplate code.

## Architecture

```
Annotations → FieldDefinitionStorage → DataFieldDefinition[]
                                         ↓
IFieldDataHolder → LazyFieldDataManager → FieldDataManager → DataField[]
                    (DCL lazy init)        (per-instance)     (AbstractField / AbstractFieldAccess)
```

- **FieldDefinitionStorage**: Scans class hierarchy for annotated fields, caches metadata globally
- **FieldDataManager**: Per-instance lifecycle manager — detects changes, serializes to network/disk
- **DataField hierarchy**: `AbstractField` (primitive values), `ObjField` (objects with codecs), `AbstractFieldAccess` (collections/maps/arrays)
- **DataSyncCodec**: Unified codec registry pairing `ByteStreamCodec` (network) with `DataCodec` (persistence)
- **Data type system**: 19-type sealed binary format, more compact than NBT, with VarInt encoding

## Key Features

- **🔁 Bidirectional Sync** — `@SyncToClient` and `@SyncToServer` handle server↔client field sync automatically. Async-safe.
- **💾 Auto Persistence** — `@SaveToDisk` fields are automatically written to/read from NBT data.
- **📡 Incremental Sync** — Built-in dirty flag mechanism transmits only changed fields via index-addressed protocol.
- **🔧 Extensible Codec** — Unified `DataSyncCodec` registry with 30+ pre-registered types, custom codec support via `@Codec`.
- **📢 Change Notification** — Per-field listener callbacks + `NotifiableHolder` system for reactive updates.
- **📦 DataComponent System** — Identity-keyed component data model via `DataComponentRegistry` + `DataComponentMap` with merge semantics.
- **🗂️ Registry Utility** — Generic registry with freeze/unfreeze lifecycle and built-in serialization (ByteStream/Data/Mojang Codec).
- **⚡ High Performance** — MethodHandle instead of reflection, FastUtil collections, multi-level caching, VarInt compact encoding.
- **🗜️ Data Type System** — Custom 19-type binary Data system, more compact than NBT Tag, with custom type extension.
- **🧩 Ready to Use** — Extend `FieldDataHolderBlockEntity` to get full capabilities out of the box.
- **🪆 Nested Holders** — `@AdditionalHolder` recursively discovers fields in nested objects with composed getter chains; `childManager` mode gives a sub-object its own dedicated `FieldDataManager`.
- **🧬 Generic Hierarchy Resolution** — `ReflectUtil` resolves full generic arguments along superclasses/interfaces (e.g. `A<T> extends HashMap<String,T>` → `[String, T]`).
- **♻️ Registry Global Codec** — `Registry` auto-registers its stream + data codecs into the global `DataSyncCodec` when frozen with a known value type.
- **🎯 Custom Strategies** — `@Strategy` for custom hash/equality change detection on complex types (ItemStack, FluidStack, etc.).

## Quick Start

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
        setChanged();  // Mark chunk for saving to disk
        DataSyncNetwork.syncBlockEntityToClient(this, false, true);  // Async-safe
    }

    // Called on client when a notifyUpdate field changes
    @Override
    public void scheduleUpdate(LogicalSide side) {
        if (side.isClient()) {
            // Re-render or refresh UI
        }
    }
}
```

### Advanced: Nested Holders

```java
public class MachineBlockEntity extends FieldDataHolderBlockEntity {

    @AdditionalHolder  // Scans InventoryData for annotated fields
    private InventoryData inventory = new InventoryData();

    @AdditionalHolder
    private EnergyData energy = new EnergyData();

    // Child-manager mode: sub-object gets its own FieldDataManager, NOT flattened.
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

    private boolean shouldSyncEnergy(long value) {
        return value > 0;  // Skip sync when empty
    }
}

class ModuleData { // managed by its own child manager (no need to implement IFieldDataHolder)
    @SaveToDisk @SyncToClient
    private int ticks;
    @SaveToDisk
    private boolean enabled = true;
}
```

> **`@AdditionalHolder` modes:**
> - **Flat (default)** — recursively hoists the sub-object's annotated fields into the parent manager, resolved via composed getters.
> - **`childManager = true`** — the sub-object is NOT flattened; a dedicated `FieldDataManager` is generated for it (wrapped in `ChildFieldDataHolder`) and the whole object is treated as one field for network/disk. The sub-object need not implement `IFieldDataHolder`. Nested child managers are supported.


### Advanced: Custom Codec

```java
// 1. Define codec (FieldDataManager auto-discovers @SaveToDisk fields on the POJO)
private static final FieldDataCodec<MyConfig> CONFIG_CODEC =
    FieldDataManager.createCodec(MyConfig.class, MyConfig::new);

// 2. Reference on the field
@SaveToDisk
@SyncToClient
@Codec(saveCodec = "CONFIG_CODEC", syncCodec = "CONFIG_CODEC")
private MyConfig config = new MyConfig();

// Fields inside MyConfig annotated with @SaveToDisk automatically participate
class MyConfig {
    @SaveToDisk
    private int maxSpeed;
    @SaveToDisk
    private String mode;
}
```

### Advanced: Entity Sync

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

> **Key points:**
> - Sync requires explicit calls to `DataSyncNetwork.syncBlockEntityToClient()` (async-safe).
> - `autoUpdate = false` fields need `markFieldsForSync()` calls to trigger sync.
> - Persistence requires manual `setChanged()` calls on the BlockEntity.
> - `syncBlockEntityToClient(be, false, true)`: `false` = incremental (only changed), `true` = only fields with `autoUpdate=true`.
> - See `TestBlockEntity` for a complete example with all features.

### Advanced: Registry Global Codec Auto-Registration

`Registry` is a generic registry that assigns stable integer IDs (for the compact network `streamCodec`) and encodes by key (for the self-describing disk `dataCodec`). When you supply the **value's runtime type**, `freeze()` automatically registers both codecs into the global `DataSyncCodec`.

```java
Registry<String, ResearchTag> TAGS = new Registry<>(
        "gtocore:research_tag", DataCodec.STRING_CODEC,  // key codec (encode/decode by key)
        t -> t.name,                                     // keyGetter: derive key from value
        ResearchTag.class);                              // value runtime type (for global registration)
TAGS.unfreeze();
TAGS.register("material", MATERIAL);
TAGS.freeze(); // ← auto: DataSyncCodec.get(ResearchTag.class) is now available
```

After freezing, any field of the registered value type can be serialized via the global `DataSyncCodec.get(...)` without manual registration.

### Advanced: Generic Hierarchy Resolution

`ReflectUtil` provides utilities to resolve full generic arguments along a field type's **superclass / interface / multi-level** hierarchy — useful when a generic ancestor fixes some parameters.

```java
// class A<T> extends HashMap<String, T>
// field: A<Integer> value;
Type fieldType = A.class.getDeclaredField("value").getGenericType(); // A<Integer>
Class<?>[] args = ReflectUtil.getResolvedGenericArguments(fieldType, HashMap.class);
// → [String.class, Integer.class]  (HashMap's 2 args: String fixed + T→Integer substituted)
```

Supports generic superclasses, generic interfaces (`List<T>`), multi-level inheritance, and bounded wildcards.

## Annotation Reference

| Annotation | Purpose | Key Attributes |
|------------|---------|---------------|
| `@SyncToClient` | Server→Client sync | `autoUpdate` (default true), `notifyUpdate`, `condition`, `listener` |
| `@SyncToServer` | Client→Server sync | `autoUpdate` (default true), `notifyUpdate`, `condition`, `listener` |
| `@SaveToDisk` | Disk persistence | `key`, `condition`, `saveNull`, `defaultValue`, `defaultValueGetter` |
| `@Access` | Force access-mode (for containers) | `createInstance` |
| `@AdditionalHolder` | Recursively scan nested object fields, or spawn a dedicated child manager | `childManager` (true = child-manager mode) |
| `@Codec` | Custom serialization | `saveCodec` / `syncCodec` / `writeToData` / `readFromData` etc. |
| `@Strategy` | Custom change detection strategy | `value` (static field name) |
| `@Generic` | Force generic-type factory resolution | — |
| `@AddToManager` | Add to manager without auto sync/persist | — |

## Data Flow

### Sync Flow (Server→Client)

```
Server tick()
  ├── Modify field values
  ├── DataSyncNetwork.syncBlockEntityToClient(be, false, true)
  │     ├── FieldDataManager.updateFieldDirtyFlags(SERVER, auto)
  │     │     └── Iterate syncToClientFields → detectChange()
  │     │           Compare current vs last snapshot → mark changed
  │     ├── FieldDataManager.writeToNetworkBuffer(SERVER, writeAll)
  │     │     ├── writeCustomSyncData()            → custom data
  │     │     └── Iterate: writeVarInt(index) + value → byte[]
  │     └── CHANNEL.send(TRACKING_CHUNK, packet)
  │
Client receive
  ├── handleBlockEntityS2C()
        ├── applyBlockEntitySyncData()
        │     └── FieldDataManager.readFromNetworkBuffer(CLIENT, data)
        │           ├── readCustomSyncData()
        │           ├── while buf: readVarInt(index) → field.readFromBuffer()
        │           └── notifyUpdate → scheduleUpdate()
```

### Persistence Flow

```
BlockEntity.saveAdditional(tag)
  └── tag.putByteArray("field_save", writeToData().writeToBytes())
        └── FieldDataManager.writeToData()
              ├── writeCustomSaveData()
              └── Iterate saveFields → field.writeToData()
                    └── Produces StringMapData(key → Data)

BlockEntity.load(tag)
  ├── Prefer "field_sync" (chunk-load sync data)
  └── Fallback "field_save" (disk persistence data)
        └── FieldDataManager.readFromData(data, VERSION)
              ├── readCustomSaveData()
              └── Iterate saveFields → field.readFromData()
```

## Documentation

### 📖 Full Documentation (open in browser)
The detailed documentation is provided as standalone HTML pages (open them directly in your browser,
not through the IDE's file viewer):

| Language | File | Content |
|----------|------|---------|
| 中文 | **[docs/index.html](docs/index.html)** | Architecture, data flow, API reference, annotation reference, usage guide |
| English | **[docs/index_en.html](docs/index_en.html)** | Architecture, data flow, API reference, usage guide |

> **How to open:** Right-click the file in your IDE and select "Open in Browser", or
> double-click the file in your file explorer. These are styled HTML pages with
> sidebar navigation — they are not designed to be viewed as raw source.

### 🌐 Translations
- **[README_zh.md](README_zh.md)** — 中文版 README（Chinese README with full examples and data flow diagrams）

## Dependencies

| Component | Version |
|-----------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.4.21 |
| Java | 21 |
| FastUtil | (bundled with Forge) |
| Lombok | (compile-time only) |

## License

GNU LGPL 3.0
