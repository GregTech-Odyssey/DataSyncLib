# Changelog

## 26.10.0-neoforge (2026-09-19) — Minecraft 1.21.1 / NeoForge

Ported from Minecraft 1.20.1 / Forge. The 1.20.1 line continues on the `1.20.1` branch and keeps
publishing its own `26.9.x` versions, so the two lines share the annotation API but not the loader.

### Breaking Changes
- **Loader and build**: ForgeGradle → **ModDevGradle 2.0.141**, Forge 47 → **NeoForge 21.1.234**,
  official (Mojang) mappings, no reobfuscation step, and the access transformer re-expressed in official
  names (ModDevGradle picks up `META-INF/accesstransformer.cfg` by convention); the mod metadata moved
  from `META-INF/mods.toml` to `META-INF/neoforge.mods.toml`, and the mod entry now receives an
  `IEventBus` instead of a `FMLJavaModLoadingContext`.
- **Networking**: the Forge `SimpleChannel` layer is gone. Sync packets are NeoForge payload types
  (`CustomPacketPayload` + Mojang `StreamCodec`) declared through `RegisterPayloadHandlersEvent`, sent
  with `PacketDistributor` and handled through `IPayloadContext` (`context.player()`, `context.flow()`,
  `context.listener().getConnectionType()`). Server→client payloads are validated by dimension, chunk,
  block-entity type and distance before they are applied.
- **Codecs**: `ByteStreamCodec`, `ByteStreamEncoder` and `ByteStreamDecoder` are removed.
  `CombinedCodec<T>` now extends `DataCodec<T>` **and** `StreamCodec<RegistryFriendlyByteBuf, T>`, and
  `StreamCodecs.fromData(DataCodec)` bridges the data side. The built-in set is registered against 1.21's
  data-component based `ItemStack`/`FluidStack`/`Component` handling.
- **Sync context**: every network read/write takes a `SyncContext` (registry access + connection type)
  because a `RegistryFriendlyByteBuf` can only be created with a `RegistryAccess`:
  `FieldDataManager#writeToNetworkBuffer(LogicalSide, SyncContext, boolean)` /
  `readFromNetworkBuffer(LogicalSide, SyncContext, byte[])`, and the same parameter reaches
  `IFieldDataHolder`, `FieldDataCodec` and the per-field buffer methods. Sending everything to one
  observer stays a forced full write (`writeToNetworkBuffer(..., true)`, which
  `DataSyncNetwork#syncBlockEntityToPlayer` uses as well).
- **NBT/serialization**: `INBTSerializable` moved to `net.neoforged.neoforge.common.util`;
  `FieldDataHolderBlockEntity` implements the 1.21 `saveAdditional(CompoundTag, HolderLookup.Provider)` /
  `loadAdditional(CompoundTag, HolderLookup.Provider)` / `getUpdateTag(HolderLookup.Provider)` signatures.
  The access transformer is back, re-expressed in official names
  (`public net.minecraft.nbt.CompoundTag tags`, `public net.minecraft.nbt.ListTag <init>(Ljava/util/List;B)V`),
  so `NbtUtil` keeps reading the tag's backing map and handing converted lists over through the typed
  constructor instead of wrapping or copying them.
- **C2S**: a serverbound payload is dimension/block-entity-or-entity-identity/distance validated and then
  decoded straight into the addressed holder with `LogicalSide.SERVER` (the 1.20.1 behaviour). The gate is
  the field declaration — only `@SyncToServer` fields are read — so no extra opt-in interface is required.

### New Features
- **`Holder` codec series**: every registry-entry codec gained its holder counterpart — `ITEM_HOLDER_CODEC`,
  `BLOCK_HOLDER_CODEC`, `FLUID_HOLDER_CODEC`, `ENTITY_TYPE_HOLDER_CODEC`,
  `BLOCK_ENTITY_TYPE_HOLDER_CODEC`, `MOB_EFFECT_HOLDER_CODEC`, `SOUND_EVENT_HOLDER_CODEC`,
  `ATTRIBUTE_HOLDER_CODEC`, `PARTICLE_TYPE_HOLDER_CODEC`, `MENU_TYPE_HOLDER_CODEC`,
  `RECIPE_TYPE_HOLDER_CODEC`. A field declared `Holder<X>` therefore resolves through the generic lookup
  `(Holder.class, X.class)` with no `@Codec` annotation; a static registry keeps the compact VarInt id on
  the wire (`StreamCodecs.ofHolder`) and the registry key on disk (`DataCodecs.ofHolder`).
- **Data-driven registries**: `DataSyncCodec.registerDynamicHolder(...)` with
  `StreamCodecs/DataCodecs.ofDynamicHolder(ResourceKey)` for registries that have no stable numeric id —
  the key travels on both paths and is resolved through the buffer's registry access on the wire (vanilla
  `ByteBufCodecs.holderRegistry`) and through `RegistryContext.current()` on disk. This is how
  `ENCHANTMENT_HOLDER_CODEC` (`Holder<Enchantment>`) is registered: 1.21 has no
  `BuiltInRegistries.ENCHANTMENT`, only the `Registries.ENCHANTMENT` key, and vanilla exchanges
  enchantments as holders.
- `RegistryContext` and the `util/cache` helpers, used by the 1.21 codec paths.
- **FastUtil list codecs**: `INT_LIST_CODEC` / `LONG_LIST_CODEC`. A non-final list field is a value
  field, so it resolves its codec through the registry; without these, an `IntList`/`LongList` field —
  and in particular an `@Access(instanceAsValue = true)` one — found no codec at all (it failed with a
  null dereference). The wire side is a VarInt size plus one VarInt per element, the disk side keeps the
  compact primitive-array form (`IntArrayData`/`LongArrayData`, no boxing). A `final` list field is
  unaffected: it stays with the FastUtil collection access layer.
- **Fix — spurious first delta for non-final access-mode fields**: `AbstractFieldAccess#detectChange`
  only primed the container's content snapshot when `mustDetect()` was true, so a non-final access-mode
  field (e.g. one whose `autoDetect` is disabled) reported one bogus change on the cycle after its first
  detect+write, which cost an extra packet. `hasChange` is now always called when the instance reference
  changed, so the snapshot can no longer lag behind.
- **NeoForge GameTest suite**: `com.gto.datasynclib.test.DataSyncGameTests` drives the framework inside a
  real server (a placed test block entity, real registries) and covers the disk round trip of every field
  family, the chunk-load tag, full and incremental network sync, the dirty-flag/marking APIs, skip
  predicates and default-value skipping, codec lookups with data- and stream-side byte round trips, the
  registry/enum/strategy helpers and the entity paths. `gradlew runGameTestServer` fails unless the server
  log reports `All N required tests passed`. The tests are grouped into batches per area (`disk`,
  `network`, `codec`, `helpers`, `entity`, `devsuite`), so the progress log and the failure report are per
  category.

### Changes
- The 1.20.1 `dataVersion == -1` compatibility branches are gone (`AbstractFieldAccess#readFromData`,
  `SerializableArrayAccess#doReadData`): the 1.21 line is new, so no old payloads exist. The format-version
  parameter itself (`dataVersion`, `FieldDataHolderBlockEntity.VERSION`/`dataVersion()`,
  `field_data_dataVersion`) is kept, it is the mechanism for future format changes.
- `ByteData`/`CharData`/`DoubleData`/`FloatData`/`IntData`/`LongData`/`ShortData`/`StringData`: the canonical
  constructor is now plain `@Deprecated` instead of `@Deprecated(forRemoval = true)`. A public record's
  canonical constructor cannot be narrower than the record, so `new ...` can never be hidden and "removal"
  was a promise the class cannot keep; the annotation is only there to steer hot paths to the cached
  `valueOf(...)` factories.

### Notes
- The 1.21 adaptations follow [MachineLib](https://github.com/GregTech-Odyssey/MachineLib)'s
  `com.moakiee.machinelib.sync` package, which is itself derived from DataSyncLib 26.9.4 and carries the
  same API names. Its `DecodeLimits` payload caps, `SnapshotScope`/`writeSnapshot` (non-consuming
  snapshot) machinery and the `ClientSyncTarget` C2S request-holder gate were deliberately **not** taken:
  this library favours raw throughput, the framework already offers forced full sync, and
  `@SyncToServer` already limits what a client payload can write.

---

## 26.9.4 (2026-09-19)

### Breaking Changes
- **Annotation attributes renamed** — the old names are gone, there are no deprecated aliases:
  - `@SaveToDisk` / `@SyncToClient` / `@SyncToServer`: `condition` → **`skipWhen`**. The referenced method is unchanged: it returns `true` to *skip* the field, which the old name did not say.
  - `@SyncToClient` / `@SyncToServer`: `autoUpdate` → **`autoDetect`** (the attribute controls automatic *change detection*, not the update itself), `notifyUpdate` → **`scheduleUpdate`** (it triggers `IFieldDataHolder#scheduleUpdate`).
  - `@SaveToDisk`: `saveNull` → **`saveEmpty`** (it also covers empty collections and empty arrays).
  - `@Conversion`: `getFunction` → **`toManaged`**, `setFunction` → **`toField`** (both name static `Function` *fields*, and the direction is the information that matters).
  - `@Access`: `createInstance` → **`instanceAsValue`** (the container instance itself becomes a nullable, replaceable value).
- Library members renamed to match: `DataFieldDefinition#autoUpdate(LogicalSide)` → `#autoDetect(LogicalSide)`, `#notifyUpdate(LogicalSide)` → `#scheduleUpdate(LogicalSide)`, `DataFieldDefinition.createInstance` → `instanceAsValue`, and the `autoOnly` parameter of `FieldDataManager#updateFieldDirtyFlags`, `DataField#detectChange` and the `DataSyncNetwork#sync*` helpers → `autoDetectOnly`.

### Changes
- Documentation and examples updated to the new attribute names (README, README_zh, CURSEFORGE, `docs/`). The `skipWhen` examples now use a predicate whose name matches its polarity (`skipEmptyEnergy` returning `true` while empty); the reference pages previously stated the inverted rule ("returns false to skip").

---

## 26.9.3 (2026-09-18)

### New Features
- **`@SaveToDisk(listener = "...")` — disk-load listener**: `@SaveToDisk` gained a `listener` attribute naming a method on the annotated field's declaring class that is invoked **after** the value has been restored from disk. Signature is `(T) -> void` — exactly one parameter of the field's declared type, primitives included (e.g. `private void onEnergyLoaded(int energy)`); the method must be non-static. It fires only when the field's key is actually present in the loaded `field_save` payload (i.e. the field was written on the previous save) and after the value has been assigned, making it the hook for rebuilding derived/cached state, invalidating neighbours, or pushing the restored value onward.
  - Supported by every field implementation: primitive fields (`IntField` … `DoubleField`, `BooleanField`, `CharField`), `@Codec` object fields (`ObjField` → `ObjCodecField` / `CustomObjCodecField`), containers/arrays via `AbstractFieldAccess` (collections, maps, arrays, `IFieldDataHolder`, `IDataSerializable`, `@AdditionalHolder(childManager = true)`).
  - For container/`createInstance` fields the listener receives the container instance, or `null` when the stored entry is a null marker; it never constructs the value itself.
  - It is **not** invoked on the network sync path (`readFromBuffer`) nor for chunk-load sync (`field_sync`), only on a disk load (`field_save`).
  - Wiring: `FieldAnnotationMetadata.readSaveListener` → `DataFieldDefinition#getSaveListener()` → each `readFromData` implementation.

### Changes
- `ObjField#readFromData`: the unreachable `value == null` early return was dropped (`read` is declared `@NotNull`), so a null decode now clears the field instead of silently keeping the previous value.
- `AbstractFieldAccess#writeToBuffer` / `#readFromData`: null-handling branches collapsed into single-condition guards, and the container decode path no longer calls `doReadData` with a null instance (no behaviour change for well-formed data).

---

## 26.9.2 (2026-09-18)

### Fixes
- **Sync packet direction dispatch (fix)**: `DataSyncNetwork` no longer registers a separate handler per packet type *per direction*. Each packet type now has a single handler (`handleBlockEntity` / `handleEntity`) that selects the client→server or server→client path from `NetworkEvent.Context.getDirection().getOriginationSide()` and applies the payload with the matching `LogicalSide` (`SERVER` for C2S, `CLIENT` for S2C). This removes the direction-by-index coupling that let a packet be routed through the wrong side's handler.
- The channel now registers two messages instead of four: index 0 = `BlockEntitySyncPacket`, index 1 = `EntitySyncPacket` (previously 0/1 = block entity C2S/S2C, 2/3 = entity C2S/S2C).

### Changes
- Build: publishing plugin `com.gto.gtopublishgradleplugin` bumped from 1.0.24 to 1.0.25.

> **Note:** the message indices were renumbered, so `PROTOCOL_VERSION` was raised to `"2"` in the same release — older builds are rejected at connect time instead of silently misrouting packets.

---

## 26.8.1 (2026-08-07)

### New Features
- **`@AdditionalHolder(childManager = true)`**: New child-manager mode. The annotated sub-object is no longer flattened into the parent manager; it gets a dedicated `FieldDataManager` (wrapped in `ChildFieldDataHolder`) and is handled as a single field through `ChildManagerAccess` (equivalent to `FieldDataHolderAccess` but for arbitrary sub-objects). Supports nested child managers.
- **Generic hierarchy resolution**: `ReflectUtil` now resolves full generic type arguments along superclasses/interfaces (`getResolvedSuperType`, `getResolvedGenericArguments`, `resolveSuperTypeBindings`). E.g. `A<T> extends HashMap<String,T>` with a field `A<Integer>` now yields `[String, Integer]`.
- **Registry global codec auto-registration**: `Registry` gained an optional value runtime type (`Class<V>`); on `freeze()` it automatically registers its `streamCodec()` + `dataCodec()` into the global `DataSyncCodec`, so fields of the registered value type serialize without manual registration.

### Changes
- `FieldDefinitionStorage.scanFields` distinguishes flat `@AdditionalHolder` from `childManager = true` (no flattening; a single child-manager field definition is created).
- `DataComponentRegistry` is unaffected (uses the classic constructor → no auto global registration).
- Dev-mode only self-tests (`DataSyncSelfTests`) and a test entity (`TestEntity`) were added to exercise disk/network round-trips, default/null skipping, child-manager, generic resolution, and registry global codec registration.

---

## 26.7.5 (2026-08-03)

### New Features
- **@Conversion annotation**: New `@Conversion` annotation enables type conversion for annotated fields via `Function<A, B>` / `Function<B, A>` static fields. This allows fields to be stored as one type while exposing another to the sync/persistence system (e.g., storing `CompoundTag` but syncing as `Map<String, Tag>`)

### Changes
- `DataFieldDefinition` now supports optional `conversionGet`/`conversionSet` function chains for transparent type conversion
- `FieldDefinitionStorage.scanFields` resolves `@Conversion`-annotated fields — looks up the `getFunction` static field (required), optionally resolves `setFunction` (skipped silently if absent, as it's not needed for final/access-mode fields)
- `ReflectUtil` simplified — removed unused field-property accessor reflection helpers; added `getFieldGenericType` for extracting generic type arguments from annotated conversion function fields
- `FieldAnnotationMetadata` now accepts raw type from the resolved conversion function's return type rather than the declared field type

---

## 26.7.4 (2026-07-22)

### Improvements
- Optimized data synchronization performance
- Improved NBT handling with native object wrapping
- Enhanced documentation with bilingual HTML docs (English + Chinese)

### Changes
- Use native object wrappers for NBT operations
- General code optimizations and cleanup

---

## 26.7.3

### Changes
- Bump version to 26.7.3
- Internal optimizations

---

## Previous Versions

For a complete changelog, see the [GitHub commits](https://github.com/GregTech-Odyssey/DataSyncLib/commits/).
