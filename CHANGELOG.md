# Changelog

## Unreleased
- **Component wire codec**: `ComponentStreamCodecs` writes a `Component` as a binary tree on the wire
  (`ByteBufCodecExtends.COMPONENT_CODEC`), while `DiskByteBufCodecs.COMPONENT_CODEC` keeps the JSON string.
- **Registered component contents**: `ComponentStreamCodecs.register(class, codec)` gives a mod's own
  `ComponentContents` the wire kind `7` — the class name as the key, then a VarInt-framed payload — where
  kind `6` would flatten it to the text it renders; a peer without the class skips the frame, warns once per
  key and decodes that node empty while its style and siblings still read.
- **Versioned files**: `VersionedFriendlyByteBuf` and `FileUtil.writeVersioned`/`readVersioned` put a VarInt
  version at the head of the file (`0` is not a version the library writes, so a plain read is the fallback);
  `DiskByteBufCodecs` is the disk half of the codec set.
- **Extension annotations**: `FieldDefinitionStorage.registerAnnotation(Class)` lets a mod hand its own field
  annotation to the library. Scanning a class then records every field carrying it as
  `annotation type → (definition, annotation instance, metadata)` — `FieldDefinitionStorage.AnnotatedField`,
  read back with `getAnnotatedFields` (the storage's own read-only array, so a lookup allocates nothing),
  `hasAnnotatedFields` and `getAnnotationTypes`. `registerAnnotation(Class, AnnotationMetadataFactory)` also
  takes an optional factory that runs once per annotated field — `(lookup, definition, managedType, annotation)` —
  and stores whatever it returns as `AnnotatedField#metadata()`, which is where an annotation's attributes
  belong instead of the scan's field metadata. A registered annotation manages
  its field on its own, like `@AddToManager`: resolved through the normal factory chain and part of the bulk
  `writeAllToValue`/`readAllFromValue` paths, but not of automatic sync or incremental save. The annotation
  must be runtime-retained and `FIELD`-targeted (validated at registration), and must be registered before
  the class is first scanned — a class's storage is cached for its lifetime.
- **`@SaveToItem` (fields that travel with the block item)**: the library registers this field annotation
  itself through the extension-annotation entry point. The block entity side is a new `IDropSave`
  interface (implemented by `FieldDataHolderBlockEntity`): `saveToItem(CompoundTag tag, boolean clone)`
  writes exactly the marked fields into the item's root tag (`fields` bytes plus the version, the same
  carrier format a save file holds), `loadFromItem(CompoundTag tag)` reads them back, and `clone`
  distinguishes creative pick-block from a real drop. The block side is the new `FieldDataHolderBlock`:
  `getDrops` attaches the payload to the block's own drop (the loot table result itself is unchanged),
  `setPlacedBy` loads it into the new block entity, and `getCloneItemStack` copies it for pick-block. A
  marked field needs no `@SaveToDisk` — the annotation manages it — and unmarked `@SaveToDisk` fields stay
  out of the item. The annotation's own attribute is `saveOnClone = false` (the field is left out of a
  creative pick-block clone but still carried by a real drop; the attribute is not named `clone`, which an
  annotation element may not be). It is parsed once at scan time by the annotation's metadata factory into
  a `SaveToItemMetadata`, kept in `AnnotatedField#metadata()` — no annotation parsing on the item path.
  The item payload goes through the save write path, so the skip condition, default value, `saveEmpty`
  rule and load listener are the field's `@SaveToDisk` ones: `@SaveToItem @SaveToDisk(enabled = false,
  skipWhen = "isUpgradeInactive")` gates the item without persisting anything. `@SaveToItem(skipWhen)` was
  removed for that reason — it could only duplicate the single predicate a field has.
- **Sync payloads are written into the caller's buffer**: `FieldDataManager.writeToNetworkBuffer(side, buf,
  writeAll)` and `readFromNetworkBuffer(side, buf)` now take the `FriendlyByteBuf` to write into / read from
  instead of building an intermediate `byte[]`, so the payload goes straight into a packet buffer (or a
  `readSlice` of one, which shares the parent's `byte[]`) and a field may ask its holder for a nested payload
  — that is how the holder-valued access types and `FieldDataCodec` carry themselves. The nested payloads
  are framed with a length prefix by the new `com.gto.datasynclib.util.ByteBufUtil`
  (`writePayload`/`readPayload` for a `short` prefix, `writeBigPayload`/`readBigPayload` for an `int` one,
  plus `writeToBytes`/`readFromBytes`/`getBytes`/`toFriendlyByteBuf` and the ready-made
  `FRIENDLY_BYTE_BUF_WRAPPER` constant for the boundaries that must stay a
  `byte[]`: the packet payload and the `field_sync` NBT array). Reading is zero-copy throughout — the
  consumer gets a slice. **The wire format changed**, so `DataSyncNetwork`'s `PROTOCOL_VERSION` is now `3`.

- **Field-subset sync into a caller's buffer**: `FieldDataManager.writeFieldsToBuffer`
  (by `DataFieldDefinition` or by field name) write just the named fields into a `FriendlyByteBuf` the
  caller owns — back to back, without a field index and without the holder's custom sync data, so it is the
  smallest payload for "push exactly these fields now" (a progress value, a GUI field).
  `readFieldsFromBuffer` applies the values in the same order through the same
  listeners and `scheduleUpdate` path as the full payload. Fields resolve through the manager's field map,
  so an unmanaged or foreign definition is rejected; change tracking is untouched, so the incremental path
  can still send those fields to its own audience. The payload is positional: both sides name the same
  fields in the same order.
- **Cached annotation definitions**: `FieldDefinitionStorage.getAnnotatedFieldDefinitions(annotationType)` is
  the definition-only view of `getAnnotatedFields`, built once with the class's storage, so a consumer that
  only needs the fields never maps the `AnnotatedField` records again. The `@SaveToItem` item path uses it
  for both the write and the read, and caches the `saveOnClone` subset per class (`SaveToItemMetadata`), so
  attaching the payload to a drop or a pick-block clone no longer allocates a filtered field list.
- **Background sync**: `FieldDataHolderBlockEntity` no longer needs a
  `DataSyncNetwork.syncBlockEntityToClient()` call in `tick` — `clearRemoved` registers the holder on a
  per-side `AsyncExecutor` (new, `com.gto.datasynclib.util`, 50 ms period) that `forge.DataSyncEvent` starts
  and stops with the server (`ServerAboutToStartEvent`/`ServerStoppedEvent`) and, on the client, with the
  connection (login/logout). `autoSync()` then tries a sync every `getMinSyncCycle()` periods and at most once
  per `getMaxSyncCycle()`; a failing task is logged and does not stop the others. The executor is modelled on
  GregTech Modern's `TaskHandler` (the GTO fork's per-level handler): an intrusive linked queue so a
  registration is one object link and no scan (no key map, no id lookup), a two-queue splice so the lock is
  never held while a task runs, O(1) unlinking for removal, and a `TaskSubscription` handle that the holder
  keeps so re-registering reuses the live entry instead of stacking a second loop. The holder unsubscribes in
  `setRemoved` and `onChunkUnloaded`, so an unloaded chunk does not leave a task behind. Explicit
  `syncBlockEntityToClient` remains supported, and is still what a plain `IFieldDataHolder` uses.
- **`enabled` on the three field annotations**: `@SaveToDisk`, `@SyncToClient` and `@SyncToServer` take
  `enabled` (default `true`). `enabled = false` keeps the field managed and keeps the annotation's
  attributes — `key`, `defaultValue`, `defaultValueGetter`, `saveEmpty`, `skipWhen`, `listener`,
  `autoDetect`, `scheduleUpdate` — while switching off the annotation's own path: the field is neither
  written to the world save nor synchronized, but it still takes part in the bulk
  `writeAllToValue`/`readAllFromValue` paths and in an extension annotation's payload, with the default-value
  filter, skip predicate and load listener of the disabled annotation applying there. It is the way to give a
  `@SaveToItem`-only field the `@SaveToDisk` attribute set without persisting it:
  `@SaveToItem @SaveToDisk(enabled = false, defaultValue = "0", listener = "onModeLoaded") int mode;`
- **Ops**: container element accessors (`ops.getInt(list, i)`, `ops.putInt(map, k, v)`, …); JSON as a
  registered custom type, with an untyped number narrowed from the literal's own digits.
- **`ValueConverters`**: converters for `SELF` values — `builder(id, type).write(encoder).read(decoder)`, the
  built-ins registered by `JavaValueOps.init()`, read back with `ops.getSelf(data, type)`. The five arrays with
  no id of their own and FastUtil's `IntList`/`LongList`/`IntSet`/`LongSet` are converters too, and
  `getTypeId`/`writeValue` name those two lists before `List`.
- **Removed**: `ValueOps.createSelf`, `OBJECT_MAP` (id `16` retired), the `java.time` creators, and the
  `dataVersion == -1` legacy layout path.
- **Behaviour**: an empty value is the absent marker for object arrays, the list/collection/map helpers and the
  field accessors, while the scalar-array codecs round-trip an empty array as one; a `BigDecimal`'s scale is a
  VarInt, so its bytes changed; `CustomTypes` is the library's own registry and a mod's object belongs in a
  converter.
- **Fixes**: `TagSerializableAccess` read with the wrong codec, `TagSerializableArrayAccess`/`ArrayAccess` read
  without a null guard, `JavaValueOps#write` had no `Object2ObjectMap` case, `@Codec`'s value hooks resolved
  with the wrong signature, and `Registry` captured its constructor parameter instead of the `keyGetter`.

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


