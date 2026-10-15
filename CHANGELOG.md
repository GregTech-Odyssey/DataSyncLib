# Changelog

## Unreleased

### New Features
- **Versioned files**: `VersionedFriendlyByteBuf` is a `FriendlyByteBuf` that also carries the version its
  payload was written at, and `FileUtil.writeVersioned(path, version, encoder, value)` /
  `FileUtil.readVersioned(path, decoder)` put that version at the head of the file as a VarInt. A stream
  codec reads it with `buf.version()`, a carrier codec with `ops.dataVersion()` (the value flavours build
  the `ValueOps` at the file's version). The version must be in `1 .. FileUtil.MAX_VERSION`; `0` is not a
  version the library writes, so a file that predates versioning is refused rather than misread, and a plain
  `try { readVersioned } catch (RuntimeException) { read }` is a working fallback. `DiskByteBufCodecs` is the
  disk half beside `ByteBufCodecExtends` (registry keys where the network half uses ids).
- **Element access shortcuts on the ops**: `ValueOps` carries the container conversions the retired
  `ListData`/`StringMapData`/`IntMapData`/`LongMapData` had as methods of their own — `ops.getInt(list, index)`,
  `ops.getString(map, "key")`, `ops.getInt(intMap, key)`, `ops.getLong(longMap, key)` and the matching
  `addXxx`/`putXxx` writers.
- **JSON as a registered custom type**: `JsonUtils` stores a `JsonElement` as a kind byte plus that kind's
  payload instead of a string, keeps a primitive's Java type, and narrows an untyped number by its digits
  (`byte`/`short`/`int`/`long`/`BigInteger`, `float`/`double`/`BigDecimal`), so an ordinary decimal costs no
  `BigDecimal`. Disk halves `ValueCodecs.JSON`/`JSON_OBJECT`/`JSON_ARRAY`, wire halves
  `ByteBufCodecExtends.JSON_CODEC`/`JSON_OBJECT_CODEC`/`JSON_ARRAY_CODEC`.
- **`ValueConverters` — converters for `SELF` values**: register one and a class the type set does not cover
  travels as an ordinary carrier value instead of Java serialization.
  `builder(id, type).write(encoder).read(decoder).build()` takes the payload's `ValueOps.Type` id (nothing of
  the converter's own is written), and the two halves are the library's `ValueEncoder`/`ValueDecoder`, handed
  the `ValueOps` doing the work. `JavaValueOps.init()` registers the built-ins (UUID, BigInteger, BigDecimal,
  the FastUtil primitive lists and sets, the five no-id arrays); lookup is by class with an optional
  `when(Predicate)`, and `ops.getSelf(data, type)` asks for a value back as a specific type.
- `SELF` is Java serialization only now, written length-framed so an `ObjectInputStream` cannot read past it;
  a nested `SELF` written by an earlier build of this refactor is no longer readable.

### Changes
- `OBJECT_MAP` is gone: a map is a `STRING_MAP`, `INT_MAP` or `LONG_MAP` (the FastUtil object-keyed maps
  included), and id `16` is retired — `createValueMap`/`getValueMap`/`isValueMap` go with it.
- The arrays with no id of their own are converters: `boolean[]` → `BYTE_ARRAY`,
  `short[]`/`char[]`/`float[]` → `INT_ARRAY`, `double[]` → `LONG_ARRAY`.
- `ValueOps#createUUID` … `#getBigDecimal` are payload definitions again — plain compositions of the carrier's
  primitives — with `ValueOpsConverters` a thin adapter; the `java.time` set and its `createInstant` …
  `getLocalDateTime` methods are gone.
- FastUtil's `IntList`/`LongList`/`IntSet`/`LongSet` get converters (the primitive array their field codecs
  write); a sorted set stays Java-serialized and an enum stays a field type.
- `getTypeId`/`writeValue` name `IntList`/`LongList` before `List`, so a FastUtil list is written as an
  `int[]` rather than a list of boxed integers; the FastUtil collection codecs in `ValueCodec` store what
  `writeValue` makes of the value and read it back through the converter.
- An empty value is the absent marker for object arrays, the list/collection/map helpers and the field
  accessors, while the eight scalar-array codecs round-trip an empty array as one;
  `FieldDataManager#readFromValue`/`#readAllFromValue`/`#readFieldsFromValue` read an absent payload as
  "nothing was stored".
- `AbstractFieldAccess`/`SerializableArrayAccess`: the `dataVersion == -1` legacy layout path is removed.
- A stored `BigDecimal` carries its scale as a `VarInt` rather than one byte, so any scale round-trips
  (`1e400`, `1e-400`); the bytes of an existing value change with it.
- `CustomTypes` is documented as the library's own registry, not an extension point: its ids are global
  `int`s that both a save file and the network carry, and a collision is fatal — a mod's own object belongs in
  a `ValueConverters` converter.
- `ValueOps.createSelf(Object)` is gone: it returned the object it was handed, and a `SELF` value already is
  the shape the carrier stores. `ValueCodec.self()` hands the value itself on and the read side is unchanged
  (`ops.getSelf(data)`, `ops.getSelf(data, type)`), so a carrier has one less method to provide.

### Fixes
- `TagSerializableAccess` wrote a tag with one codec and read it with another, so every `INBTSerializable`
  field lost its state and an old save failed outright.
- `TagSerializableArrayAccess`/`ArrayAccess` read a payload without a null guard, so a field whose slots were
  all empty threw on load.
- `JavaValueOps#write` had no `Object2ObjectMap` case although `getTypeId` reported `OBJECT_MAP`, so the map
  was written with string-map framing under that id.
- `@Codec(writeToValue=…, readFromValue=…)` resolved and invoked its hooks with the wrong signature; they now
  take `(ValueOps, T)` / `(ValueOps, Object)` on the declaring instance.
- `Registry` captured its constructor parameter instead of the resolved `keyGetter`, which disabled the
  documented null → `getKeyByMap` fallback.

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

