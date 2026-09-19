package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.FieldDefinitionStorage;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.util.EnumUtil;
import com.gto.datasynclib.util.FluidStackArrayHashStrategy;
import com.gto.datasynclib.util.FluidStackHashStrategy;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.ItemStackArrayHashStrategy;
import com.gto.datasynclib.util.ItemStackHashStrategy;
import com.gto.datasynclib.util.Registry;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Development-only test suite for {@link TestBlockEntity}, covering the public API surface of the
 * framework end to end: definition scanning and lookup, codec resolution, disk and network
 * round-trips (full and incremental), the block-entity NBT paths ({@code saveAdditional}/{@code load}
 * and the chunk-load {@code field_sync} tag), listeners (including {@code @SaveToDisk(listener)}),
 * dirty-flag/marking APIs, single-field helpers, skip predicates, default-value skipping, child managers,
 * {@code @Conversion}, Forge {@code INBTSerializable} (scalar and array), the built-in
 * registry/enum helpers, and the registered change-detection strategies for {@code ItemStack}/
 * {@code FluidStack} (scalar and array).
 *
 * <p>Every section is wrapped, so a broken feature is reported as a failure instead of aborting the
 * suite. Runs once per JVM from {@link TestBlockEntity#serverTick}, i.e. in a development client or
 * server where the registries are live. It never touches a {@code Level}: the block entity is built
 * directly and only level-free APIs are exercised ({@code setChanged()} and the network send helpers
 * are no-ops without a level, which is asserted as well).</p>
 */
public final class TestBlockEntityTests {

    private static final String LOG = "[TestBlockEntityTests] ";
    private static final BlockPos POS = new BlockPos(1, 2, 3);

    private final List<String> failures = new ArrayList<>();
    private int checks;

    public static void runAll() {
        new TestBlockEntityTests().run();
    }

    private void run() {
        section("definitions", this::definitions);
        section("codecs", this::codecs);
        section("codecLookupPolicy", this::codecLookupPolicy);
        section("diskRoundTrip", this::diskRoundTrip);
        section("nbtRoundTrip", this::nbtRoundTrip);
        section("chunkLoadSync", this::chunkLoadSync);
        section("networkFull", this::networkFull);
        section("networkIncremental", this::networkIncremental);
        section("listeners", this::listeners);
        section("dirtyFlags", this::dirtyFlags);
        section("skipPredicatesAndDefaults", this::skipPredicatesAndDefaults);
        section("singleFieldApi", this::singleFieldApi);
        section("childManagerAndConversion", this::childManagerAndConversion);
        section("registryEnumAndStrategy", this::registryEnumAndStrategy);
        section("levelFreeNoThrow", this::levelFreeNoThrow);
        report();
    }

    // ==================== sections ====================

    /**
     * Definition scanning and lookup.
     *
     * <p><b>Usage:</b> every annotated field of a class hierarchy is scanned once and cached. Look a
     * definition up by storage key ({@code manager.getFieldDefinition("i")}), by
     * {@link java.lang.reflect.Field} or by value; {@link FieldDefinitionStorage#get(Class)} is the same
     * cache without a holder instance. Fields without a sync/save/manager annotation are never managed.</p>
     */
    private void definitions() {
        var storage = FieldDefinitionStorage.get(TestBlockEntity.class);
        expect("definitions.storage", storage != null, true);
        TestBlockEntity holder = newEntity();
        var manager = holder.getFieldDataManager();

        expect("definitions.saved", manager.hasSaveFields(), true);
        expect("definitions.synced", manager.hasSyncFields(LogicalSide.SERVER), true);
        expect("definitions.syncedToServer", manager.hasSyncFields(LogicalSide.CLIENT), true);

        // Keys come from the field name unless @SaveToDisk(key = ...) overrides it.
        for (var key : List.of("i", "uuids", "stacks", "stack", "fluid", "tanks", "directions", "uuidSet", "map", "aabb", "a",
                "b", "ints", "floats", "intList", "object2IntMap", "handler", "handlerArray", "module",
                "globalPos", "sectionPos", "vec3i", "withDefault", "skipped", "loaded", "tagData")) {
            expect("definitions.key." + key, manager.getFieldDefinition(key) != null, true);
        }

        // Sync-only fields are managed but not persisted, and vice versa.
        expect("definitions.syncOnlyManaged", manager.getFieldDefinition("objectHolder") != null, true);
        expect("definitions.manualManaged", manager.getFieldDefinition("manual") != null, true);
        expect("definitions.unannotatedSkipped", manager.getFieldDefinition("isDirty") == null, true);
        expect("definitions.helperFieldsSkipped", manager.getFieldDefinition("loadedListenerCalls") == null, true);

        // Field-based lookup, including a field the framework does not manage.
        Field managed = declaredField("i");
        Field unmanaged = declaredField("isDirty");
        expect("definitions.byField", manager.getFieldDefinition(managed) != null, true);
        expect("definitions.byFieldUnmanaged", manager.getFieldDefinition(unmanaged) == null, true);
        // Value-based lookup is bucketed by the field's declared type and matches by identity.
        expect("definitions.byValue", manager.getFieldDefinition(TestBlockEntity.B.class, holder.b) != null, true);
        expect("definitions.byValueMiss", manager.getFieldDefinition(TestBlockEntity.B.class, newEntity().b) == null, true);

        // The child-manager POJO and the @Codec POJO get their own storages on demand.
        expect("definitions.childModuleScan", FieldDefinitionStorage.get(TestBlockEntity.ChildModule.class)
                .getFieldDefinition(declaredField(TestBlockEntity.ChildModule.class, "ticks")) != null, true);
        expect("definitions.codecPojoScan", FieldDefinitionStorage.get(TestBlockEntity.A.class)
                .getFieldDefinition(declaredField(TestBlockEntity.A.class, "a")) != null, true);
    }

    /**
     * Built-in codec registry.
     *
     * <p><b>Usage:</b> {@link DataSyncCodec#get(Class)} resolves the codec of a field type and exposes
     * {@code dataWriter/dataReader} (disk) and {@code streamWriter/streamReader} (network), so both paths
     * can be driven directly — which is what the assertions below do for primitive arrays, enums,
     * ItemStack and the hand-written {@code GlobalPos}/{@code SectionPos}/{@code Vec3i} pairs.</p>
     */
    private void codecs() {
        expect("codecs.primitiveArray", DataSyncCodec.get(int[].class) != null, true);
        expect("codecs.primitiveArrayFloat", DataSyncCodec.get(float[].class) != null, true);
        expect("codecs.stringArray", DataSyncCodec.get(String[].class) != null, true);
        expect("codecs.enum", DataSyncCodec.get(Direction.class) != null, true);
        expect("codecs.enumArray", DataSyncCodec.get(Direction[].class) != null, true);
        expect("codecs.itemStack", DataSyncCodec.get(ItemStack.class) != null, true);
        expect("codecs.fluidStack", DataSyncCodec.get(FluidStack.class) != null, true);
        expect("codecs.aabb", DataSyncCodec.get(AABB.class) != null, true);
        expect("codecs.compoundTag", DataSyncCodec.get(CompoundTag.class) != null, true);
        expect("codecs.component", DataSyncCodec.get(Component.class) != null, true);

        // Hand-written codecs added to the built-in set.
        expect("codecs.globalPos", DataSyncCodec.get(GlobalPos.class) != null, true);
        expect("codecs.sectionPos", DataSyncCodec.get(SectionPos.class) != null, true);
        expect("codecs.vec3i", DataSyncCodec.get(Vec3i.class) != null, true);
        expect("codecs.mobEffect", DataSyncCodec.get(MobEffect.class) != null, true);
        expect("codecs.recipeType", DataSyncCodec.get(RecipeType.class) != null, true);

        // Round-trip a couple of them through the data side directly.
        var globalPos = GlobalPos.of(Level.NETHER, new BlockPos(4, 5, 6));
        var codec = DataSyncCodec.get(GlobalPos.class);
        expect("codecs.globalPosRoundTrip", globalPos,
                codec.dataReader.decode(codec.dataWriter.encode(globalPos), 0));
        var sectionPos = SectionPos.of(7, 8, 9);
        var sectionCodec = DataSyncCodec.get(SectionPos.class);
        expect("codecs.sectionPosRoundTrip", sectionPos.asLong(),
                sectionCodec.dataReader.decode(sectionCodec.dataWriter.encode(sectionPos), 0).asLong());
        var vec = new Vec3i(-1, 2, -3);
        var vecCodec = DataSyncCodec.get(Vec3i.class);
        expect("codecs.vec3iRoundTrip", vec, vecCodec.dataReader.decode(vecCodec.dataWriter.encode(vec), 0));
    }

    /**
     * What {@code get}/{@code contains} resolve and what they deliberately do not.
     *
     * <p><b>Usage notes:</b> registration is an <b>exact class match</b> — there is no assignable-from
     * fallback, so a subtype (e.g. {@code MutableComponent} for {@code Component}) must be registered
     * explicitly. Enums and object arrays are generated on demand and always resolve, primitives never do,
     * and a codec referenced from {@code @Codec} lives in the annotation instead of the global registry.</p>
     */
    private void codecLookupPolicy() {
        // Primitives never have a codec.
        expect("policy.primitiveNull", DataSyncCodec.get(int.class) == null, true);
        expect("policy.primitiveContains", DataSyncCodec.contains(int.class), false);
        // Enums and object arrays report a codec even without registration (generated on demand).
        expect("policy.generatedEnum", DataSyncCodec.contains(Direction.class), true);
        expect("policy.generatedArray", DataSyncCodec.contains(String[].class), true);
        // Deliberate: no assignable-from fallback, a subtype must be registered explicitly.
        expect("policy.exactMatchOnly", DataSyncCodec.get(MutableComponent.class) == null, true);
        expect("policy.supertypeStillThere", DataSyncCodec.get(Component.class) != null, true);
        // @Codec referenced codecs live in the annotation, not in the global registry.
        expect("policy.codecAnnotationNotGlobal", DataSyncCodec.get(TestBlockEntity.A.class) == null, true);
        // Access-mode types are not codec-backed either.
        expect("policy.holderNotCodec", DataSyncCodec.get(ItemStackHandler.class) == null, true);
        // Generic lookup stays empty until a generic codec is registered.
        expect("policy.genericLookupMiss", DataSyncCodec.get(List.class, String.class) == null, true);
        expect("policy.genericContainsMiss", DataSyncCodec.contains(List.class, String.class), false);
    }

    /**
     * Disk persistence for {@code @SaveToDisk}.
     *
     * <p><b>Usage:</b> {@code manager.writeToData()} returns a {@link StringMapData} keyed by field name
     * (or {@code @SaveToDisk(key = ...)}) and {@code readFromData(data, version)} restores it;
     * {@code writeAllToData()/readAllFromData()} additionally carry fields that are only
     * {@code @AddToManager}. Values equal to their configured default, and values skipped by a
     * {@code skipWhen} predicate, never appear in the map.</p>
     */
    private void diskRoundTrip() {
        TestBlockEntity src = mutate(newEntity());
        Data saved = src.getFieldDataManager().writeToData();
        expect("disk.notEmpty", saved instanceof StringMapData, true);

        TestBlockEntity dst = newEntity();
        dst.getFieldDataManager().readFromData(saved, FieldDataHolderBlockEntity.VERSION);
        expectSaved("disk", src, dst);

        // writeAllToData/readAllFromData additionally carry managed-but-not-@SaveToDisk fields.
        Data all = src.getFieldDataManager().writeAllToData();
        TestBlockEntity dstAll = newEntity();
        dstAll.getFieldDataManager().readAllFromData(all, FieldDataHolderBlockEntity.VERSION);
        expectSaved("diskAll", src, dstAll);

        // A field skipped by its skipWhen predicate or equal to its default must not appear in the map.
        if (saved instanceof StringMapData map) {
            expect("disk.defaultSkipped", map.containsKey("withDefault"), false);
            expect("disk.skipWhenSkipped", map.containsKey("skipped"), false);
            expect("disk.handlerPresent", map.containsKey("handler"), true);
            expect("disk.handlerArrayPresent", map.containsKey("handlerArray"), true);
            expect("disk.childModulePresent", map.containsKey("module"), true);
        }
    }

    /**
     * The block-entity NBT path around {@link FieldDataHolderBlockEntity}.
     *
     * <p><b>Usage:</b> {@code saveAdditional(tag)} stores the serialized state as one byte array under
     * {@code field_save} plus the current {@code field_data_dataVersion}, and {@code load(tag)} reads it
     * back and hands that version to every codec so data can be migrated. The suite reaches these through
     * the {@code saveToTag()}/{@code loadFromTag()} hooks because they are {@code protected}.</p>
     */
    private void nbtRoundTrip() {
        TestBlockEntity src = mutate(newEntity());
        CompoundTag tag = src.saveToTag();

        expect("nbt.versionWritten", tag.getInt("field_data_dataVersion"), FieldDataHolderBlockEntity.VERSION);
        expect("nbt.saveArrayPresent", tag.get("field_save") instanceof ByteArrayTag, true);
        expect("nbt.saveArrayNotEmpty", tag.getByteArray("field_save").length > 0, true);

        // Wipe the destination, then restore from NBT and compare.
        TestBlockEntity dst = newEntity();
        clear(dst);
        dst.loadFromTag(tag);
        expectSaved("nbt", src, dst);

        // Mutating a field past its default makes it persist.
        TestBlockEntity other = newEntity();
        other.withDefault = 8;
        other.skipped = -1; // skipMe returns true → still skipped
        CompoundTag otherTag = other.saveToTag();
        if (otherTag.get("field_save") instanceof ByteArrayTag array) {
            Data data = Data.readData(array.getAsByteArray());
            if (data instanceof StringMapData map) {
                expect("nbt.nonDefaultWritten", map.containsKey("withDefault"), true);
                expect("nbt.negativeSkipWhenSkipped", map.containsKey("skipped"), false);
            } else {
                expect("nbt.nonDefaultMap", true, false);
            }
        } else {
            expect("nbt.nonDefaultArray", true, false);
        }
    }

    /**
     * Chunk-load synchronization.
     *
     * <p><b>Usage:</b> nothing to override — {@code getUpdateTag()} already embeds the full
     * {@code @SyncToClient} state under {@code field_sync}, so a player entering the chunk receives the
     * current values without a dedicated packet. {@code load(tag)} prefers {@code field_sync} over
     * {@code field_save}, and only the sync-to-client direction travels.</p>
     */
    private void chunkLoadSync() {
        TestBlockEntity src = mutate(newEntity());
        CompoundTag updateTag = src.getUpdateTag();
        expect("chunkLoad.syncArrayPresent", updateTag.get("field_sync") instanceof ByteArrayTag, true);

        TestBlockEntity client = newEntity();
        clear(client);
        client.loadFromTag(updateTag); // load() prefers field_sync over field_save
        expectSynced("chunkLoad", src, client);
        // field_sync carries only the sync-to-client direction.
        expect("chunkLoad.saveOnlyUntouched", client.i, newEntity().i);
    }

    /**
     * Full network round-trip in both directions.
     *
     * <p><b>Usage:</b> the sender calls {@code writeToNetworkBuffer(side, true)} and the receiver applies
     * the bytes with {@code readFromNetworkBuffer(oppositeSide, bytes)}. {@code LogicalSide.SERVER}
     * serializes {@code @SyncToClient} fields, {@code LogicalSide.CLIENT} serializes
     * {@code @SyncToServer} ones (here the {@code ObjNotifiableHolder} field).</p>
     */
    private void networkFull() {
        TestBlockEntity src = mutate(newEntity());
        byte[] server = src.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);

        TestBlockEntity client = newEntity();
        clear(client);
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, server);
        expectSynced("netFull", src, client);

        // Client → server direction: only @SyncToServer fields, and the listener must fire.
        TestBlockEntity sender = newEntity();
        sender.objectHolder.value = "from-client";
        byte[] toServer = sender.getFieldDataManager().writeToNetworkBuffer(LogicalSide.CLIENT, true);

        TestBlockEntity receiver = newEntity();
        receiver.b.receiverCalls = 0;
        receiver.getFieldDataManager().readFromNetworkBuffer(LogicalSide.SERVER, toServer);
        expect("netFull.toServer", sender.objectHolder.value, receiver.objectHolder.value);
    }

    /**
     * Incremental sync — the normal per-tick path.
     *
     * <p><b>Usage:</b> {@code updateFieldDirtyFlags(side, autoDetectOnly)} detects what changed and
     * {@code writeToNetworkBuffer(side, false)} sends only those fields as a sequence of
     * (fieldIndex, payload) pairs; a field declared with {@code autoDetect = false} needs an explicit
     * {@code markFieldsForSync(name)} first. The assertions also pin down that an incremental buffer stays
     * smaller than a full one, and that a single changed slot of an {@code ItemStackHandler[]} does not
     * re-send the other slots.</p>
     */
    private void networkIncremental() {
        TestBlockEntity src = mutate(newEntity());
        byte[] full = src.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);

        // One small change only → the incremental buffer must be far smaller than the full one.
        src.synced = 31337;
        src.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        byte[] incremental = src.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, false);
        expect("netIncremental.notEmpty", incremental.length > 0, true);
        expect("netIncremental.smallerThanFull", incremental.length < full.length, true);

        // Applying it to a client that already has the full state updates just that field.
        TestBlockEntity client = newEntity();
        clear(client);
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, full);
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, incremental);
        expect("netIncremental.applied", src.synced, client.synced);
        expectSynced("netIncremental.clientState", src, client);

        // A single changed slot of an INBTSerializable array must not re-send the other slots.
        TestBlockEntity arrayFull = mutate(newEntity());
        byte[] arrayFullBytes = arrayFull.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);
        arrayFull.handlerArray[1].setStackInSlot(0, new ItemStack(Items.EMERALD, 9));
        arrayFull.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        byte[] arrayIncremental = arrayFull.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, false);
        expect("netIncremental.arraySmaller", arrayIncremental.length < arrayFullBytes.length, true);

        // markFieldsForSync forces a field with autoDetect = false to be sent. The entity is primed
        // first, because a brand-new one reports every field as changed on its first check.
        TestBlockEntity manual = mutate(newEntity());
        manual.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        manual.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);
        manual.manual = 4711;
        manual.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        expect("netIncremental.manualNotAutoDetected",
                manual.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, false).length, 0);
        manual.getFieldDataManager().markFieldsForSync("manual");
        byte[] marked = manual.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, false);
        expect("netIncremental.manualAfterMark", marked.length > 0, true);
        TestBlockEntity manualClient = newEntity();
        manualClient.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, marked);
        expect("netIncremental.manualValue", manual.manual, manualClient.manual);
    }

    /**
     * Change-notification hooks.
     *
     * <p><b>Usage:</b> {@code @SaveToDisk(listener = "method")} receives the restored value after a disk
     * load; {@code @SyncToClient(listener = ...)} and {@code @SyncToServer(listener = ...)} receive
     * {@code (newValue, oldValue)} on the receiving side. The listener must sit on the declaring class and
     * take exactly the field type (primitives included). A field typed {@code ObjNotifiableHolder} fires
     * its own receiver listener instead.</p>
     */
    private void listeners() {
        // @SaveToDisk(listener): fires on load with the restored value.
        TestBlockEntity src = newEntity();
        src.loaded = 4242;
        CompoundTag tag = src.saveToTag();

        TestBlockEntity dst = newEntity();
        dst.loadedListenerCalls = 0;
        dst.lastLoaded = -1;
        dst.loadFromTag(tag);
        expect("listener.saveCalls", dst.loadedListenerCalls, 1);
        expect("listener.saveValue", dst.lastLoaded, 4242);
        expect("listener.saveFieldRestored", dst.loaded, 4242);

        // Sync listener: invoked as (newValue, oldValue) on the receiving side.
        TestBlockEntity sender = newEntity();
        sender.synced = 99;
        byte[] bytes = sender.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);

        TestBlockEntity receiver = newEntity();
        receiver.syncedListenerCalls = 0;
        receiver.lastSyncedOld = -1;
        receiver.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, bytes);
        expect("listener.syncCalls", receiver.syncedListenerCalls, 1);
        expect("listener.syncOldValue", receiver.lastSyncedOld, 0);
        expect("listener.syncNewValue", receiver.synced, 99);

        // Nested holder: reading B's ObjNotifiableHolder fires its receiver listener.
        TestBlockEntity nestedSender = newEntity();
        nestedSender.b.c.value = "nested";
        byte[] nestedBytes = nestedSender.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);

        TestBlockEntity nestedReceiver = newEntity();
        nestedReceiver.b.receiverCalls = 0;
        nestedReceiver.b.lastReceived = null;
        nestedReceiver.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, nestedBytes);
        expect("listener.nestedCalls", nestedReceiver.b.receiverCalls, 1);
        expect("listener.nestedValue", nestedReceiver.b.lastReceived, "nested");
    }

    /**
     * Dirty-flag and marking API of {@link FieldDataManager}.
     *
     * <p><b>Usage:</b> mark by name or by definition ({@code markFieldsForSync(...)}), or force the whole
     * manager with {@code markAsChanged()}. A mark survives until the field is actually written, which
     * {@code isChanged()} reports; {@code clearAllChangeMarks()} resets every field, and unknown field
     * names fail loudly.</p>
     */
    private void dirtyFlags() {
        TestBlockEntity be = newEntity();
        FieldDataManager manager = be.getFieldDataManager();

        be.synced = 5;
        expect("dirty.detectsChange", manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), true);
        // The dirty flag stays set until the field is actually written.
        expect("dirty.stickyUntilWrite", manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), true);
        expect("dirty.managerFlag", manager.isChanged(), true);

        byte[] written = manager.writeToNetworkBuffer(LogicalSide.SERVER, false);
        expect("dirty.written", written.length > 0, true);
        expect("dirty.clearedAfterWrite", manager.isChanged(), false);
        expect("dirty.noChangeAfterWrite", manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), false);

        // Explicit marking by name and by definition.
        manager.markFieldsForSync("synced");
        expect("dirty.markByName", manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), true);
        manager.writeToNetworkBuffer(LogicalSide.SERVER, false);
        manager.clearAllChangeMarks();
        expect("dirty.clearAll", manager.isChanged(), false);
        expect("dirty.clearAllClearsFields", manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), false);

        var definition = manager.getFieldDefinition("synced");
        manager.markFieldsForSync(definition);
        expect("dirty.markByDefinition", manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), true);

        manager.markAsChanged();
        expect("dirty.manualFlag", manager.isChanged(), true);
        manager.clearChanged();
        expect("dirty.manualFlagCleared", manager.isChanged(), false);

        // Unknown names are rejected loudly.
        expect("dirty.unknownNameThrows", throwsRuntime(() -> manager.markFieldsForSync("nope")), true);
        expect("dirty.unknownFieldDataThrows",
                throwsRuntime(() -> manager.writeFieldToData("nope")), true);
    }

    /**
     * Skip predicates and default-value skipping.
     *
     * <p><b>Usage:</b> {@code @SaveToDisk(defaultValue = "7")} keeps matching values out of the saved
     * data, and {@code @SaveToDisk(skipWhen = "method")} skips a field whenever that boolean-returning
     * method returns true. The same {@code skipWhen} attribute exists on {@code @SyncToClient} and
     * {@code @SyncToServer} for the network side.</p>
     */
    private void skipPredicatesAndDefaults() {
        TestBlockEntity be = newEntity();
        be.skipped = -5; // skipMe returns true → skipped
        be.withDefault = 7;  // equals the @SaveToDisk default → skipped

        Data data = be.getFieldDataManager().writeToData();
        boolean skipped = !(data instanceof StringMapData map)
                || (!map.containsKey("skipped") && !map.containsKey("withDefault"));
        expect("skipPredicates.skipped", skipped, true);

        // The skip-predicate method itself also has to be reachable for the annotation to resolve.
        expect("skipPredicates.method", be.skipMe(-1), true);
        expect("skipPredicates.methodFalse", be.skipMe(1), false);
    }

    /**
     * Single-field read/write helpers, useful for partial saves or external storage.
     *
     * <p><b>Usage:</b> {@code writeFieldToData(name)} with {@code readFieldFromData(data, version, name)}
     * for one field, and {@code writeFieldsToData(names...)} with
     * {@code readFieldsFromData(data, version, names...)} for a subset. Unknown names throw
     * {@link IllegalArgumentException}.</p>
     */
    private void singleFieldApi() {
        TestBlockEntity src = newEntity();
        src.i = 777;
        src.aabb = new AABB(9, 9, 9, 10, 10, 10);

        Data i = src.getFieldDataManager().writeFieldToData("i");
        expect("single.iNotNone", i.isNone(), false);
        Data aabb = src.getFieldDataManager().writeToData();

        TestBlockEntity dst = newEntity();
        dst.getFieldDataManager().readFieldFromData(i, FieldDataHolderBlockEntity.VERSION, "i");
        expect("single.iRestored", dst.i, 777);

        Data both = src.getFieldDataManager().writeFieldsToData("i", "aabb");
        expect("single.multiWritten", both instanceof StringMapData, true);

        TestBlockEntity dst2 = newEntity();
        dst2.getFieldDataManager().readFieldsFromData(both, FieldDataHolderBlockEntity.VERSION, "i", "aabb");
        expect("single.multiI", dst2.i, 777);
        expect("single.multiAabb", dst2.aabb, src.aabb);
        expect("single.unknownReadThrows", throwsRuntime(() ->
                dst2.getFieldDataManager().readFieldFromData(i, 0, "nope")), true);
    }

    /**
     * Nested state: child managers and type conversion.
     *
     * <p><b>Usage:</b> {@code @AdditionalHolder(childManager = true)} gives a plain POJO its own
     * {@code FieldDataManager} — the sub-object needs no interface and is serialized as a single field,
     * recursively. {@code @Conversion(toManaged = "staticField")} exposes a field as another type (here a
     * {@code CompoundTag} managed as a {@code Map}), which is how a field can be stored and compared
     * through a more convenient representation.</p>
     */
    private void childManagerAndConversion() {
        TestBlockEntity src = newEntity();
        src.module.ticks = 900;
        src.module.name = "child";
        src.module.values.clear();
        src.module.values.add(3);
        src.module.values.add(4);
        src.tagData.putInt("converted", 21);

        // Child manager: persisted and synced as one field.
        Data data = src.getFieldDataManager().writeToData();
        TestBlockEntity dst = newEntity();
        dst.getFieldDataManager().readFromData(data, FieldDataHolderBlockEntity.VERSION);
        expect("child.ticks", dst.module.ticks, 900);
        expect("child.name", dst.module.name, "child");
        expect("child.values", dst.module.values, List.of(3, 4));

        byte[] bytes = src.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, true);
        TestBlockEntity client = newEntity();
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, bytes);
        expect("child.netTicks", client.module.ticks, 900);
        expect("child.netValues", client.module.values, List.of(3, 4));

        // @Conversion: the CompoundTag is managed as a Map<String, Tag> and must survive both paths.
        expect("conversion.disk", dst.tagData.getInt("converted"), 21);
        expect("conversion.net", client.tagData.getInt("converted"), 21);
    }

    /**
     * Registry, enum and strategy helpers.
     *
     * <p><b>Usage:</b> a {@link Registry} assigns stable ids/keys and, once frozen, registers its own codecs
     * into {@link DataSyncCodec} for its value type; {@link EnumUtil#isFixed(Class)} decides whether an enum
     * is persisted by ordinal or by name; {@code registerStrategy(Type.class, strategy)} replaces change
     * detection for a field type — looked up by <b>exact</b> type, so an array such as {@code ItemStack[]}
     * needs its own entry instead of inheriting the element's. For {@code ItemStack[]} that entry is also
     * the only way to notice an element edited in place, because {@code ItemStack} has no content-based
     * {@code hashCode}/{@code equals}; {@code FluidStack} does have them, so its array registration is
     * about choosing the comparison level (amount/NBT or not) and about symmetry with the scalar field.
     * For a custom element type the array strategy is derived with
     * {@link HashUtil#arrayStrategy(Hash.Strategy)} instead of a hand-written class.</p>
     */
    private void registryEnumAndStrategy() {
        // Fixed enums: ordinal persistence is only used for the registered types.
        expect("enum.fixedLogicalSide", EnumUtil.isFixed(LogicalSide.class), true);
        expect("enum.fixedDirection", EnumUtil.isFixed(Direction.class), true);
        expect("enum.notFixedAxisDirection", EnumUtil.isFixed(Direction.AxisDirection.class), false);

        expect("enum.byName", EnumUtil.getEnum(Direction.class, "NORTH"), Direction.NORTH);
        expect("enum.serializedName", EnumUtil.getSerializedName(Direction.NORTH), "north");
        expect("enum.bySerializedName", EnumUtil.getSerializedEnum(Direction.class, "north"), Direction.NORTH);
        expect("enum.unknownName", EnumUtil.getEnum(Direction.class, "SIDEWAYS") == null, true);

        // A registry registers its own codecs globally once frozen.
        record Tag2(String name, int value) {
        }
        var registry = new Registry<String, Tag2>("test:be_tag", DataCodec.STRING_CODEC, t -> t.name, Tag2.class);
        registry.unfreeze();
        registry.register("one", new Tag2("one", 1));
        registry.register("two", new Tag2("two", 2));
        registry.freeze();

        expect("registry.frozen", registry.isFrozen(), true);
        expect("registry.size", registry.values().size(), 2);
        expect("registry.keyLookup", registry.get("two").value(), 2);
        var global = DataSyncCodec.get(Tag2.class);
        expect("registry.globalCodec", global != null, true);
        if (global != null) {
            expect("registry.globalRoundTrip", global.dataReader.decode(global.dataWriter.encode(new Tag2("two", 2)), 0).value(), 2);
        }

        // Strategy levels: only the count differs → ALL sees a change, ITEM does not.
        var a = new ItemStack(Items.IRON_INGOT, 1);
        var b = new ItemStack(Items.IRON_INGOT, 4);
        expect("strategy.allSeesCount", ItemStackHashStrategy.ALL.equals(a, b), false);
        expect("strategy.itemIgnoresCount", ItemStackHashStrategy.ITEM.equals(a, b), true);
        expect("strategy.allSeesItem", ItemStackHashStrategy.ALL.equals(a, new ItemStack(Items.GOLD_INGOT, 1)), false);
        expect("strategy.emptyVsNull", ItemStackHashStrategy.ALL.equals(null, ItemStack.EMPTY), true);

        // The array counterpart mirrors those levels slot by slot.
        expect("strategy.arrayAllSeesCount",
                ItemStackArrayHashStrategy.ALL.equals(new ItemStack[]{a}, new ItemStack[]{b}), false);
        expect("strategy.arrayItemIgnoresCount",
                ItemStackArrayHashStrategy.ITEM.equals(new ItemStack[]{a}, new ItemStack[]{b}), true);
        expect("strategy.arrayNullVsEmpty",
                ItemStackArrayHashStrategy.ALL.equals(new ItemStack[]{null}, new ItemStack[]{ItemStack.EMPTY}), true);

        // A scalar ItemStack field uses the registered ALL strategy, so an in-place count change is seen.
        TestBlockEntity scalar = newEntity();
        scalar.stack = new ItemStack(Items.IRON_INGOT, 1);
        prime(scalar);
        scalar.stack.setCount(2);
        expect("strategy.scalarDetectsCount",
                scalar.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true), true);

        // An ItemStack[] field needs the registered *array* strategy for the same thing: without it the
        // elements would be compared by identity and an edited stack would be invisible.
        TestBlockEntity array = newEntity();
        array.stacks[0] = new ItemStack(Items.IRON_INGOT, 1);
        array.stacks[1] = new ItemStack(Items.IRON_INGOT, 1);
        prime(array);
        array.stacks[0].setCount(5);
        expect("strategy.arrayDetectsInPlace",
                array.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true), true);

        // Replacing a slot keeps working as well.
        prime(array);
        array.stacks[1] = new ItemStack(Items.GOLD_INGOT, 2);
        expect("strategy.arrayDetectsReplacement",
                array.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true), true);

        // FluidStack *does* have content-based equals/hashCode, so FluidStack[] would already be noticed
        // unregistered; the registered array strategy is there for the level selection (and symmetry
        // with the scalar field). Only the amount differs here.
        var water1 = new FluidStack(Fluids.WATER, 1);
        var water4 = new FluidStack(Fluids.WATER, 4);
        expect("strategy.fluidAllSeesAmount", FluidStackHashStrategy.ALL.equals(water1, water4), false);
        expect("strategy.fluidIgnoresAmount", FluidStackHashStrategy.FLUID.equals(water1, water4), true);
        expect("strategy.fluidSeesKind",
                FluidStackHashStrategy.FLUID.equals(water1, new FluidStack(Fluids.LAVA, 1)), false);
        expect("strategy.fluidNullVsEmpty", FluidStackHashStrategy.ALL.equals(null, FluidStack.EMPTY), true);
        expect("strategy.fluidArrayAllSeesAmount",
                FluidStackArrayHashStrategy.ALL.equals(new FluidStack[]{water1}, new FluidStack[]{water4}), false);
        expect("strategy.fluidArrayIgnoresAmount",
                FluidStackArrayHashStrategy.FLUID.equals(new FluidStack[]{water1}, new FluidStack[]{water4}), true);
        expect("strategy.fluidArrayNullVsEmpty",
                FluidStackArrayHashStrategy.ALL.equals(new FluidStack[]{null}, new FluidStack[]{FluidStack.EMPTY}), true);

        // A scalar FluidStack field uses the registered ALL strategy, so an in-place amount change is seen.
        TestBlockEntity fluidScalar = newEntity();
        fluidScalar.fluid = new FluidStack(Fluids.WATER, 1000);
        prime(fluidScalar);
        fluidScalar.fluid.setAmount(1200);
        expect("strategy.fluidScalarDetectsAmount",
                fluidScalar.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true), true);

        // Same for an element of the FluidStack[] field.
        TestBlockEntity fluidArray = newEntity();
        fluidArray.tanks[0] = new FluidStack(Fluids.WATER, 1000);
        prime(fluidArray);
        fluidArray.tanks[0].setAmount(1500);
        expect("strategy.fluidArrayDetectsInPlace",
                fluidArray.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true), true);

        // HashUtil.arrayStrategy derives the array strategy of *any* element type from its scalar
        // strategy, so a custom type needs no hand-written array class. A coarser level (length only)
        // shows the element strategy is really the one being applied slot by slot.
        Hash.Strategy<String> lengthOnly = new Hash.Strategy<>() {

            @Override
            public int hashCode(String o) {
                return o == null ? 0 : o.length();
            }

            @Override
            public boolean equals(String x, String y) {
                return x == null ? y == null : y != null && x.length() == y.length();
            }
        };
        var stringArrays = HashUtil.arrayStrategy(lengthOnly);
        expect("strategy.genericArrayHash", stringArrays.hashCode(new String[]{"aa"}), stringArrays.hashCode(new String[]{"bb"}));
        expect("strategy.genericArrayEquals", stringArrays.equals(new String[]{"aa"}, new String[]{"bb"}), true);
        expect("strategy.genericArraySlotCount", stringArrays.equals(new String[]{"aa"}, new String[]{"aa", "b"}), false);
        expect("strategy.genericArrayNullArray", stringArrays.hashCode(null), 0);
        expect("strategy.genericArrayNullSlot", stringArrays.equals(new String[]{null}, new String[]{null}), true);

        // The named array strategies of the library are built on it, so both agree.
        expect("strategy.genericMatchesItemArray",
                HashUtil.arrayStrategy(ItemStackHashStrategy.ALL).equals(new ItemStack[]{a}, new ItemStack[]{b}),
                ItemStackArrayHashStrategy.ALL.equals(new ItemStack[]{a}, new ItemStack[]{b}));
        expect("strategy.genericMatchesFluidArray",
                HashUtil.arrayStrategy(FluidStackHashStrategy.FLUID).equals(new FluidStack[]{water1}, new FluidStack[]{water4}),
                FluidStackArrayHashStrategy.FLUID.equals(new FluidStack[]{water1}, new FluidStack[]{water4}));
    }

    /**
     * Nothing here requires a {@code Level}.
     *
     * <p><b>Usage:</b> {@code setChanged()} and the {@code DataSyncNetwork} send helpers are no-ops without
     * one, so a test (or any headless helper) can drive the framework from a plain server tick.</p>
     */
    private void levelFreeNoThrow() {
        TestBlockEntity be = newEntity();
        // These APIs are safe (no-ops) without a Level: the suite must not blow up on them.
        be.updateTick();
        be.setChanged();
        be.isDirty = true;
        be.updateTick();
        be.getFieldDataManager().markFieldsForSync("i");
        expect("levelFree.noThrow", true, true);
    }

    // ==================== helpers ====================

    /**
     * Builds a fresh entity for a test. The block entity type comes from the registry, so this only works
     * in a live game — which is the point: the suite runs from the development server tick.
     */
    private TestBlockEntity newEntity() {
        return new TestBlockEntity(POS, Blocks.IRON_BLOCK.defaultBlockState());
    }

    /**
     * Marks everything dirty and writes it once, so the following assertions start from a clean baseline.
     * A brand-new entity reports every field as changed on its first check, which would otherwise make a
     * later "was this change detected?" assertion pass for the wrong reason.
     */
    private void prime(TestBlockEntity be) {
        be.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        be.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, false);
    }

    /**
     * Gives every managed family a non-default value so a round-trip has something to carry.
     */
    private TestBlockEntity mutate(TestBlockEntity be) {
        be.i = 1234;
        for (int k = 0; k < be.uuids.length; k++) {
            be.uuids[k] = "u" + k;
        }
        be.stacks[0] = new ItemStack(Items.DIAMOND, 5);
        be.stack = new ItemStack(Items.GOLD_INGOT, 3);
        be.tanks[0] = new FluidStack(Fluids.LAVA, 250);
        be.tanks[1] = new FluidStack(Fluids.WATER, 4000);
        be.fluid = new FluidStack(Fluids.LAVA, 750);
        be.directions[0][0] = Direction.UP;
        be.uuidSet.clear();
        be.uuidSet.add("set-a");
        be.map.clear();
        be.map.put(9, true);
        be.aabb = new AABB(0.5, 1.5, 2.5, 3.5, 4.5, 5.5);
        be.a = new TestBlockEntity.A(77);
        be.b.a = 88;
        be.b.b = Items.GOLD_INGOT;
        be.b.c.value = "b-value";
        be.objectHolder.value = "holder";
        be.ints[0] = 5;
        be.ints[2] = 9;
        be.floats[1] = 2.5F;
        be.intList.clear();
        be.intList.add(11);
        be.intList.add(22);
        be.object2IntMap.clear();
        be.object2IntMap.put("x", 42);
        be.handler.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 7));
        be.handlerArray[1].setStackInSlot(1, new ItemStack(Items.DIAMOND, 3));
        be.module.ticks = 1500;
        be.module.name = "mod-x";
        be.module.values.clear();
        be.module.values.add(7);
        be.module.values.add(8);
        be.globalPos = GlobalPos.of(Level.NETHER, new BlockPos(10, 11, 12));
        be.sectionPos = SectionPos.of(4, 5, 6);
        be.vec3i = new Vec3i(7, 8, 9);
        be.loaded = 4242;
        be.synced = 99;
        be.tagData.putInt("aaa", 99);
        return be;
    }

    /**
     * Resets the persisted/synced state of a freshly built entity so restoration is provable (a value that
     * was never overwritten would otherwise look like a successful round-trip). Container fields are
     * emptied in place rather than nulled, because the access layer works on the live container.
     */
    private void clear(TestBlockEntity be) {
        be.i = 0;
        Arrays.fill(be.uuids, null);
        Arrays.fill(be.stacks, null);
        be.stack = ItemStack.EMPTY;
        Arrays.fill(be.tanks, null);
        be.fluid = FluidStack.EMPTY;
        for (var row : be.directions) {
            Arrays.fill(row, null);
        }
        be.uuidSet.clear();
        be.map.clear();
        be.aabb = new AABB(0, 0, 0, 0, 0, 0);
        be.a = new TestBlockEntity.A(0);
        be.b.a = 0;
        be.b.b = null;
        be.b.c.value = null;
        be.objectHolder.value = null;
        Arrays.fill(be.ints, 0);
        Arrays.fill(be.floats, 0F);
        be.intList.clear();
        be.object2IntMap.clear();
        be.handler.setStackInSlot(0, ItemStack.EMPTY);
        be.handlerArray[1].setStackInSlot(1, ItemStack.EMPTY);
        be.module.ticks = 0;
        be.module.name = "";
        be.module.values.clear();
        be.globalPos = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
        be.sectionPos = SectionPos.of(0, 0, 0);
        be.vec3i = Vec3i.ZERO;
        be.loaded = 0;
        be.synced = 0;
        be.tagData.getAllKeys().clear();
    }

    private void expectSaved(String prefix, TestBlockEntity src, TestBlockEntity dst) {
        expect(prefix + ".i", src.i, dst.i);
        // ItemStack has no equals/hashCode, so content has to be compared through ItemStack.matches.
        expect(prefix + ".stack", ItemStack.matches(src.stack, dst.stack), true);
        expect(prefix + ".uuids", Arrays.equals(src.uuids, dst.uuids), true);
        expect(prefix + ".stacks", stacksEqual(src.stacks, dst.stacks), true);
        expect(prefix + ".fluid", fluidsEqual(src.fluid, dst.fluid), true);
        expect(prefix + ".tanks", fluidsEqual(src.tanks, dst.tanks), true);
        expect(prefix + ".directions", Arrays.deepEquals(src.directions, dst.directions), true);
        expect(prefix + ".uuidSet", src.uuidSet, dst.uuidSet);
        expect(prefix + ".map", src.map, dst.map);
        expect(prefix + ".aabb", src.aabb, dst.aabb);
        expect(prefix + ".a.a", src.a.a, dst.a.a);
        expect(prefix + ".b.a", src.b.a, dst.b.a);
        expect(prefix + ".b.b", src.b.b, dst.b.b);
        expect(prefix + ".ints", Arrays.equals(src.ints, dst.ints), true);
        expect(prefix + ".floats", Arrays.equals(src.floats, dst.floats), true);
        expect(prefix + ".intList", src.intList, dst.intList);
        expect(prefix + ".object2IntMap", src.object2IntMap, dst.object2IntMap);
        expect(prefix + ".handler", src.handler.serializeNBT(), dst.handler.serializeNBT());
        expect(prefix + ".handlerArray", handlerArrayTag(src), handlerArrayTag(dst));
        expect(prefix + ".module.ticks", src.module.ticks, dst.module.ticks);
        expect(prefix + ".module.name", src.module.name, dst.module.name);
        expect(prefix + ".module.values", src.module.values, dst.module.values);
        expect(prefix + ".globalPos", src.globalPos, dst.globalPos);
        expect(prefix + ".sectionPos", src.sectionPos.asLong(), dst.sectionPos.asLong());
        expect(prefix + ".vec3i", src.vec3i, dst.vec3i);
        expect(prefix + ".loaded", src.loaded, dst.loaded);
        expect(prefix + ".tagData", src.tagData, dst.tagData);
    }

    private void expectSynced(String prefix, TestBlockEntity src, TestBlockEntity dst) {
        expect(prefix + ".a.a", src.a.a, dst.a.a);
        // Note: only @SyncToClient fields of the nested holder travel (b.c), not its @SaveToDisk ones.
        expect(prefix + ".b.c.value", src.b.c.value, dst.b.c.value);
        expect(prefix + ".stack", ItemStack.matches(src.stack, dst.stack), true);
        expect(prefix + ".fluid", fluidsEqual(src.fluid, dst.fluid), true);
        expect(prefix + ".ints", Arrays.equals(src.ints, dst.ints), true);
        expect(prefix + ".floats", Arrays.equals(src.floats, dst.floats), true);
        expect(prefix + ".intList", src.intList, dst.intList);
        expect(prefix + ".object2IntMap", src.object2IntMap, dst.object2IntMap);
        expect(prefix + ".handler", src.handler.serializeNBT(), dst.handler.serializeNBT());
        expect(prefix + ".handlerArray", handlerArrayTag(src), handlerArrayTag(dst));
        expect(prefix + ".module.ticks", src.module.ticks, dst.module.ticks);
        expect(prefix + ".module.name", src.module.name, dst.module.name);
        expect(prefix + ".globalPos", src.globalPos, dst.globalPos);
        expect(prefix + ".synced", src.synced, dst.synced);
        expect(prefix + ".tagData", src.tagData, dst.tagData);
    }

    private List<CompoundTag> handlerArrayTag(TestBlockEntity be) {
        List<CompoundTag> tags = new ArrayList<>();
        for (ItemStackHandler handler : be.handlerArray) {
            tags.add(handler.serializeNBT());
        }
        return tags;
    }

    /** {@code ItemStack} has no {@code equals}, so compare slot by slot through {@code matches}. */
    private boolean stacksEqual(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            ItemStack x = a[i];
            ItemStack y = b[i];
            if (x == null || y == null) {
                if (x != y) return false;
            } else if (!ItemStack.matches(x, y)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares fluid kind, amount and NBT, treating an absent tag and an empty tag as equal — NBT codecs
     * are free to normalize one into the other, and {@code Objects.equals} would not.
     */
    private boolean fluidsEqual(FluidStack a, FluidStack b) {
        if (a == null || b == null) return a == b;
        if (a.getAmount() != b.getAmount() || a.getFluid() != b.getFluid()) return false;
        var tagA = a.getTag();
        var tagB = b.getTag();
        if (tagA == null || tagA.isEmpty()) return tagB == null || tagB.isEmpty();
        return tagA.equals(tagB);
    }

    private boolean fluidsEqual(FluidStack[] a, FluidStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!fluidsEqual(a[i], b[i])) return false;
        }
        return true;
    }

    private Field declaredField(String name) {
        return declaredField(TestBlockEntity.class, name);
    }

    private Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean throwsRuntime(Runnable action) {
        try {
            action.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private void section(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(name + " threw " + t);
            System.err.println(LOG + "FAIL section " + name + " threw " + t);
            t.printStackTrace();
        }
    }

    private void expect(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(name);
            System.err.println(LOG + "FAIL " + name);
        }
    }

    private <T> void expect(String name, T expected, T actual) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures.add(name + " expected=" + expected + " actual=" + actual);
            System.err.println(LOG + "FAIL " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    private void report() {
        if (failures.isEmpty()) {
            DataSyncLib.LOGGER.info(LOG + "ALL " + checks + " CHECKS PASSED");
        } else {
            DataSyncLib.LOGGER.error(LOG + failures.size() + " FAILURE(S) out of " + checks + ": " + failures);
        }
    }
}
