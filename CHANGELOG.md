# Changelog

## 26.10.5

### New Features
- **`ValueConverters` — a converter registry for `SELF` values**: a class the type set does not cover used to
  be written with Java's own serializer, which is fragile across class changes and opaque in a save
  file. Register a converter and it travels as an ordinary carrier value instead:

  ```java
  ValueConverters.Converter.<UUID>builder(ValueOps.Type.LONG_ARRAY, UUID.class)
          .write((ops, uuid) -> ops.createUUID(uuid))
          .read((ops, data) -> ops.getUUID(data))
          .build();
  ```

  - The `builder` id is the `ValueOps.Type` id of the payload the converter writes — not an id of the
    converter's own, and nothing is written for it: a converted value is stored exactly as its payload,
    under the payload's own id, with no wrapper and no flag byte. A UUID is a `LONG_ARRAY` in a save
    file whether a field codec wrote it or it was opaque. Two converters whose payloads are the same
    type share the id; a class can only be registered once.
  - The payload functions are handed the `ValueOps` doing the work and build the payload from its
    primitives, so a converter is not tied to one carrier's shapes.
  - `JavaValueOps.init()` registers the built-in set during mod construction — `UUID`, `BigInteger`,
    `BigDecimal`, `Instant`, `Duration`, `LocalDate`, `LocalTime`, `LocalDateTime`, and the five arrays
    the type set has no id of its own for — each of which stores the payload the matching
    `ValueOps#createXxx` produces, so a type reaches the same bytes whichever way it is written. A
    downstream mod registers its own from its constructor.
  - Lookup is by class on both sides over one set of entries: a `ClassValue` (with a generation counter,
    so a registration that arrives after a lookup is still seen) resolves a value's class on the way
    out, and a `Reference2ReferenceOpenHashMap` resolves the class on the way back. A converter whose
    job is a whole family adds a `Predicate<Class<?>>` with `when(...)`; predicates are consulted in
    registration order, so a narrow one goes first.
  - `ValueOps#getSelf(Object, Class)` is the read side with a target type: a value that already is
    that type comes back as it is, a converted one is turned back by its converter, and anything else
    reports the mismatch instead of guessing. `getSelf(Object)` stays the identity.
  - `SELF` is now only Java serialization, and its bytes are written as a length-framed byte array: an
    `ObjectInputStream` reads ahead into a block buffer, so handing it the shared stream would let it
    swallow the values framed after this one. **Note:** a nested `SELF` value written by an earlier
    build of this refactor is no longer readable; the pre-refactor type set had no `SELF` id at all.

### Changes
- `OBJECT_MAP` is gone. A map is a `STRING_MAP`, an `INT_MAP` or a `LONG_MAP` — the FastUtil
  object-keyed maps included, so a map whose keys are not strings is refused when it is written
  instead of being stored under an id of its own. The id `16` it held is retired: nothing may take it,
  and a value an older build stored under it is not readable. `createValueMap`/`getValueMap`/
  `isValueMap` go with it.
- The arrays with no id of their own — `boolean[]`, `short[]`, `char[]`, `float[]`, `double[]` — are
  converters like every other derived type rather than cases inside the carrier: `boolean[]` stores a
  `BYTE_ARRAY`, `short[]`/`char[]`/`float[]` an `INT_ARRAY` and `double[]` a `LONG_ARRAY`, each through
  the matching `createXxxArray`. They used to fall through to `SELF` and be Java-serialized, which is
  not what those methods and the matching codecs write.
- `ValueOps#createUUID` … `#getLocalDateTime` are the payload definitions again — plain compositions of
  the carrier's own primitives (`createLongArray`, `createByteArray`, …) rather than calls into the
  converter registry — and `ValueOpsConverters` is a thin adapter that stores what they produce.
- An empty value is now stored as the absent marker consistently: the object-array codec, the eight
  scalar-array codecs, the eight array accessors and the `list`/`collection`/`array`/`map` helpers all
  write the null marker for an empty value, and every read side checks `ops.isNull(data)` before
  asking the carrier for a list or an array. The retired registry-level array codec did the same, so
  the bytes are unchanged for these cases.
- `FieldDataManager#readFromValue` / `#readAllFromValue` / `#readFieldsFromValue` treat an absent
  payload as "nothing was stored" again: a holder whose fields are all at their default writes the
  null marker, and reading it back is a no-op rather than a `ClassCastException`.
- `AbstractFieldAccess` / `SerializableArrayAccess`: the `dataVersion == -1` legacy layout path was
  removed together with its documentation — this build does not read the Data-era `uid`/`payload`
  nesting.

### Fixes
- `TagSerializableAccess` wrote a tag with one codec and read it with another, so every
  `INBTSerializable` field lost its state and an old save failed outright.
- `TagSerializableArrayAccess` and `ArrayAccess` read a payload without a null guard, so a field whose
  slots were all empty threw on load.
- `JavaValueOps#write` had no `Object2ObjectMap` case although `getTypeId` reports `OBJECT_MAP`, so
  the map was written with string-map framing under that id.
- `@Codec(writeToValue=…, readFromValue=…)` instance-method mode resolved and invoked the hooks with
  the wrong signature; they now take `(ValueOps, T)` / `(ValueOps, Object)` on the declaring instance,
  as the annotation documents.
- `Registry` captured its constructor parameter instead of the resolved `keyGetter`, which disabled
  the documented null → `getKeyByMap` fallback.

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
