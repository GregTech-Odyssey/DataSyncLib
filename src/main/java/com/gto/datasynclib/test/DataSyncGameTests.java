package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.RegistryContext;
import com.gto.datasynclib.SyncContext;
import com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity;
import com.gto.datasynclib.datastream.codec.CombinedCodec;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.network.DataSyncNetwork;
import com.gto.datasynclib.util.EnumUtil;
import com.gto.datasynclib.util.FluidStackArrayHashStrategy;
import com.gto.datasynclib.util.FluidStackHashStrategy;
import com.gto.datasynclib.util.ItemStackArrayHashStrategy;
import com.gto.datasynclib.util.ItemStackHashStrategy;
import com.gto.datasynclib.util.NbtUtil;
import com.gto.datasynclib.util.Registry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime, real-level verification suite for DataSyncLib, driven by the NeoForge GameTest framework
 * instead of the framework's own test entry points.
 *
 * <h3>What this suite is for</h3>
 * <p>It proves that persistence and synchronization behave correctly inside a <em>live server
 * level</em>: the block entities it drives are real {@code TestBlockEntity} instances, the registry
 * lookups are the ones the running server hands out, the chunk-load tag is produced by the vanilla
 * {@code getUpdateTag}/{@code handleUpdateTag} pair, and the entity path is exercised on a real
 * spawned {@code TestEntity}. The disk path is also exercised once through a block entity that is
 * genuinely placed in the world with {@code BlockEntityType} resolution, which is the step the
 * level-free suites cannot cover.</p>
 *
 * <h3>How to run it</h3>
 * <pre>{@code gradlew runGameTestServer}</pre>
 * <p>NeoForge only registers tests whose template namespace is in
 * {@code neoforge.enabledGameTestNamespaces}; {@code build.gradle} sets that to
 * {@code datasynclib} for the {@code gameTestServer} run, which is also the value of the
 * {@code @GameTestHolder} annotation below (the mod id). The template is the shipped
 * {@code data/datasynclib/structure/empty.nbt} (a 1x1 air structure), so everything is placed at
 * small relative coordinates such as {@code BlockPos.ZERO}.</p>
 *
 * <h3>How it relates to the other suites</h3>
 * <p>It <strong>complements</strong> {@link TestBlockEntityTests}: that suite runs from
 * {@link TestBlockEntity#serverTick} (once per JVM), needs no level at all, and covers the same API
 * surface plus internals (definition scanning, single-field helpers, {@code levelFreeNoThrow}, ...)
 * with its own pass/fail bookkeeping. This one asserts through
 * {@link GameTestHelper#assertTrue}/{@link GameTestHelper#assertValueEqual} so each area shows up as
 * a separately reported GameTest, and it is the place where the behaviour is verified against the
 * world's real registries and blocks. A failure here is a hard GameTest failure, and
 * {@code devSuiteRunFromTheTickPathReportsClean} makes a failure of {@code TestBlockEntityTests} one
 * too (that suite itself only logs).</p>
 *
 * <h3>Registry context (1.21)</h3>
 * <p>The disk codecs read their {@code HolderLookup.Provider} from {@link RegistryContext}, the
 * network codecs get theirs from {@link SyncContext}; both are derived from
 * {@code h.getLevel().registryAccess()} in every test, so no test depends on another one having run
 * first.</p>
 */
@GameTestHolder("datasynclib")
@PrefixGameTestTemplate(false)
public final class DataSyncGameTests {

    private DataSyncGameTests() {
    }

    /**
     * Proves the full disk round trip of every managed field family through the block-entity NBT path.
     *
     * <p>One entity is really placed in the level (so its type, block state and manager come from the
     * game), filled with non-default values in every family, and saved with
     * {@code saveToTag(lookup)}; a second entity is cleared and restored with
     * {@code loadFromTag(tag, lookup)}. Primitives, primitive arrays, {@code String[]} with nulls,
     * {@code ItemStack}/{@code FluidStack} (scalar and array, custom-name components included),
     * {@code Direction[][]}, {@code Set}/{@code Map}, FastUtil containers, {@code ItemStackHandler}
     * and its array, {@code GlobalPos}/{@code SectionPos}/{@code Vec3i}, the {@code @Conversion}
     * {@code CompoundTag}, the {@code @AdditionalHolder(childManager = true)} POJO and the
     * {@code @Codec} value must all come back identical, while {@code @SaveToDisk(defaultValue)} and
     * {@code skipWhen} fields must stay out of the written map and the
     * {@code @SaveToDisk(listener)} hook must fire exactly once with the restored value.</p>
     */
    @GameTest(template = "empty", batch = "disk")
    public static void diskRoundTripPersistsEveryFieldFamily(GameTestHelper h) {
        var lookup = h.getLevel().registryAccess();
        var context = SyncContext.neoforge(lookup);
        try (var ignored = RegistryContext.use(lookup)) {
            TestBlockEntity testBlockEntity = placeBlockEntity(h, BlockPos.ZERO);
            mutate(testBlockEntity);

            CompoundTag tag = testBlockEntity.saveToTag(lookup);
            h.assertTrue(tag.get("field_save") instanceof ByteArrayTag, "field_save byte array missing");
            h.assertValueEqual(tag.getInt("field_data_dataVersion"), TestBlockEntity.VERSION,
                    "field_data_dataVersion");

            TestBlockEntity restored = newEntity();
            clear(restored);
            restored.loadFromTag(tag, lookup);
            expectSaved(h, "disk", testBlockEntity, restored);

            // @SaveToDisk(listener = "onLoaded"): exactly one call, with the restored value.
            h.assertValueEqual(restored.loadedListenerCalls, 1, "loadedListenerCalls");
            h.assertValueEqual(restored.lastLoaded, 4242, "lastLoaded");

            // The @Codec POJO is written and read through its FieldDataCodec, not a global codec.
            h.assertValueEqual(restored.a.a, 77, "a.a");
            // The child-manager POJO keeps its own manager on the restored instance.
            h.assertValueEqual(restored.module.name, "mod-x", "module.name");
            h.assertValueEqual(restored.module.values, List.of(7, 8), "module.values");

            // Fields with a default / a skip predicate never reach field_save.
            if (Data.readData(tag.getByteArray("field_save")) instanceof StringMapData map) {
                h.assertFalse(map.containsKey("withDefault"), "default-valued field was written");
                h.assertFalse(map.containsKey("skipped"), "skipWhen-skipped field was written");
                h.assertTrue(map.containsKey("handler"), "INBTSerializable field missing");
                h.assertTrue(map.containsKey("module"), "child-manager field missing");
            } else {
                h.fail("field_save did not decode to a StringMapData");
            }

            // saveEmpty: an all-null array is written only when the field opts in. `stacks` is emptied
            // on purpose here, so the pair isolates the attribute from the array being unwritable.
            TestBlockEntity allNull = newEntity();
            Arrays.fill(allNull.stacks, null);
            if (allNull.getFieldDataManager().writeToData() instanceof StringMapData emptyMap) {
                h.assertFalse(emptyMap.containsKey("stacks"), "all-null array without saveEmpty was written");
                h.assertTrue(emptyMap.containsKey("savedEmpty"), "all-null array with saveEmpty was skipped");
            } else {
                h.fail("writeToData did not decode to a StringMapData");
            }

            // instanceAsValue on disk: a null instance is not written at all (saveEmpty is off), so
            // the field is simply absent from field_save — the explicit null marker is what the
            // network path carries (see the network batch).
            TestBlockEntity nullList = newEntity();
            nullList.optionalInts = null;
            if (Data.readData(nullList.saveToTag(lookup).getByteArray("field_save")) instanceof StringMapData nullMap) {
                h.assertFalse(nullMap.containsKey("optionalInts"), "null instanceAsValue value was written to disk");
            } else {
                h.fail("field_save did not decode to a StringMapData");
            }

            // Also drive a full network write from the placed entity so the registry context of the
            // level is really the one used for the wire format.
            TestBlockEntity client = newEntity();
            clear(client);
            client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context,
                    testBlockEntity.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true));
            expectSynced(h, "diskPlacedNet", testBlockEntity, client);

            h.setBlock(BlockPos.ZERO, net.minecraft.world.level.block.Blocks.AIR);
        }
        h.succeed();
    }

    /**
     * Proves that the chunk-load tag carries only the {@code @SyncToClient} direction.
     *
     * <p>{@code getUpdateTag(lookup)} (= {@code saveSyncTag}) embeds a forced full network write under
     * {@code field_sync}; applying it through {@code handleUpdateTag(lookup)} (= {@code loadSyncTag})
     * restores the synced families on a fresh entity while a value that is persisted only
     * ({@code @SaveToDisk} without {@code @SyncToClient}) stays at its default. It also checks that
     * the chunk-load tag does not contain the saved {@code field_save} payload, so a client can never
     * mistake saved state for synced state.</p>
     */
    @GameTest(template = "empty", batch = "disk")
    public static void chunkLoadSyncCarriesOnlySyncToClientState(GameTestHelper h) {
        var lookup = h.getLevel().registryAccess();
        var context = SyncContext.neoforge(lookup);
        try (var ignored = RegistryContext.use(lookup)) {
            TestBlockEntity source = mutate(newEntity());
            CompoundTag updateTag = source.saveSyncTag(lookup);
            h.assertTrue(updateTag.get("field_sync") instanceof ByteArrayTag, "field_sync byte array missing");
            h.assertFalse(updateTag.contains("field_save", Tag.TAG_BYTE_ARRAY),
                    "chunk-load tag also carried the disk payload");

            TestBlockEntity client = newEntity();
            clear(client);
            client.loadSyncTag(updateTag, lookup);
            expectSynced(h, "chunkLoad", source, client);

            // Save-only field (no @SyncToClient) must not have travelled.
            h.assertValueEqual(client.i, 0, "save-only field changed by chunk-load sync");
            h.assertFalse(client.i == source.i, "the save-only value reached the client");
            // And the initial sync is a full write even though nothing was marked dirty.
            h.assertTrue(context.registries() == lookup, "sync context did not use the level registries");
        }
        h.succeed();
    }

    /**
     * Proves the full network round trip over the library's own payload format.
     *
     * <p>{@code writeToNetworkBuffer(LogicalSide.SERVER, context, true)} serializes every
     * {@code @SyncToClient} field and {@code readFromNetworkBuffer(LogicalSide.CLIENT, context, bytes)}
     * applies it on the receiving side. {@code ItemStack} and {@code FluidStack} are compared with
     * {@code ItemStack.matches}/{@code FluidStack.matches} (item, count and the 1.21 data components
     * such as {@code CUSTOM_NAME}), containers with the comparisons the level-free suite uses, and the
     * listener on the receiving side must observe the new value.</p>
     */
    @GameTest(template = "empty", batch = "network")
    public static void fullNetworkRoundTripCarriesSyncedFamilies(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        TestBlockEntity source = mutate(newEntity());
        byte[] full = source.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
        h.assertTrue(full.length > 0, "full network buffer was empty");

        TestBlockEntity client = newEntity();
        clear(client);
        client.syncedListenerCalls = 0;
        client.lastSyncedOld = -1;
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, full);

        expectSynced(h, "netFull", source, client);
        h.assertTrue(ItemStack.matches(source.stack, client.stack), "scalar ItemStack components lost on wire");
        h.assertTrue(FluidStack.matches(source.fluid, client.fluid), "scalar FluidStack components lost on wire");
        h.assertTrue(nbtEquals(source.tagData, client.tagData), "@Conversion CompoundTag lost on wire");
        // @SyncToClient(listener = "onSynced") fires as (newValue, oldValue) on the receiving side.
        h.assertValueEqual(client.syncedListenerCalls, 1, "syncedListenerCalls");
        h.assertValueEqual(client.lastSyncedOld, 0, "lastSyncedOld");
        h.assertValueEqual(client.synced, 99, "synced value");

        // Save-only state must not travel with the sync payload.
        h.assertValueEqual(client.i, 0, "save-only field travelled over the wire");

        // instanceAsValue: the null instance is an explicit marker on the wire, so a receiver that
        // holds a list ends up with null instead of keeping it.
        TestBlockEntity nullSource = newEntity();
        byte[] nullInstance = nullSource.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
        TestBlockEntity nullTarget = newEntity();
        nullTarget.optionalInts = new IntArrayList(new int[]{9});
        nullTarget.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, nullInstance);
        h.assertTrue(nullTarget.optionalInts == null, "instanceAsValue null marker did not travel");
        h.succeed();
    }

    /**
     * Proves the incremental (per-tick) sync path and the manual marking API.
     *
     * <p>After a full write primes the dirty tracking, changing a single field and calling
     * {@code updateFieldDirtyFlags(LogicalSide.SERVER, true)} produces a buffer that is strictly
     * smaller than the full one and that updates exactly that field on a receiver which already holds
     * the full state. A field declared {@code @SyncToClient(autoDetect = false)} is <em>not</em>
     * included until {@code markFieldsForSync("manual")} is called, after which it is sent and applied
     * correctly.</p>
     */
    @GameTest(template = "empty", batch = "network")
    public static void incrementalNetworkSyncSendsOnlyChangedFields(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        TestBlockEntity source = mutate(newEntity());
        byte[] full = source.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
        prime(source, context);

        source.synced = 31337;
        h.assertTrue(source.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                "changed field was not detected as dirty");
        byte[] incremental = source.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, false);
        h.assertTrue(incremental.length > 0, "incremental buffer was empty");
        h.assertTrue(incremental.length < full.length, "incremental buffer was not smaller than the full one");

        TestBlockEntity client = newEntity();
        clear(client);
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, full);
        client.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, incremental);
        h.assertValueEqual(client.synced, 31337, "incremental value was not applied");
        // Truly incremental: applying a delta must not disturb state the delta does not mention.
        h.assertValueEqual(client.manual, 17, "incremental sync reset an unrelated synced field");
        h.assertTrue(client.i == 0, "incremental sync overwrote save-only state");

        // A single edited slot of an INBTSerializable[] must not re-send the other slots.
        TestBlockEntity arrayHolder = mutate(newEntity());
        byte[] arrayFull = arrayHolder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
        prime(arrayHolder, context);
        arrayHolder.handlerArray[1].setStackInSlot(0, new ItemStack(Items.EMERALD, 9));
        arrayHolder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        byte[] arrayIncremental = arrayHolder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, false);
        h.assertTrue(arrayIncremental.length > 0, "edited handler slot was not detected");
        h.assertTrue(arrayIncremental.length < arrayFull.length, "handler[] delta was not smaller than the full write");

        // autoDetect = false: silent until an explicit mark, then sent.
        TestBlockEntity manual = mutate(newEntity());
        byte[] manualFull = manual.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
        prime(manual, context);
        manual.manual = 4711;
        manual.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        byte[] silent = manual.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, false);
        h.assertValueEqual(silent.length, 0, "autoDetect = false field was auto-detected");

        manual.getFieldDataManager().markFieldsForSync("manual");
        byte[] marked = manual.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, false);
        h.assertTrue(marked.length > 0, "marked field was not sent");
        h.assertTrue(marked.length < manualFull.length, "marked delta was not smaller than the full write");
        TestBlockEntity manualClient = newEntity();
        manualClient.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, marked);
        h.assertValueEqual(manualClient.manual, 4711, "marked field was not applied");
        h.succeed();
    }

    /**
     * Proves the dirty-flag and marking API of {@link FieldDataManager}.
     *
     * <p>{@code updateFieldDirtyFlags} detects a change and keeps reporting it until the field is
     * written; {@code markFieldsForSync(String...)} and {@code markFieldsForSync(DataFieldDefinition)}
     * force a field into the next write, {@code isChanged()} reports the manager flag,
     * {@code clearAllChangeMarks()} resets every field, and an unknown field name fails loudly instead
     * of being ignored.</p>
     */
    @GameTest(template = "empty", batch = "network")
    public static void dirtyFlagsAndMarkingApiBehaveAsDocumented(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        TestBlockEntity testBlockEntity = newEntity();
        FieldDataManager manager = testBlockEntity.getFieldDataManager();

        testBlockEntity.synced = 5;
        h.assertTrue(manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), "change not detected");
        h.assertTrue(manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), "dirty flag was not sticky before write");
        h.assertTrue(manager.isChanged(), "manager dirty flag not set");

        byte[] written = manager.writeToNetworkBuffer(LogicalSide.SERVER, context, false);
        h.assertTrue(written.length > 0, "dirty field was not written");
        h.assertFalse(manager.isChanged(), "manager dirty flag survived the write");
        h.assertFalse(manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), "field stayed dirty after write");

        // Marking by name and by definition.
        manager.markFieldsForSync("synced");
        h.assertTrue(manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), "markFieldsForSync(name) had no effect");
        manager.writeToNetworkBuffer(LogicalSide.SERVER, context, false);
        manager.clearAllChangeMarks();
        h.assertFalse(manager.isChanged(), "clearAllChangeMarks left the manager dirty");
        h.assertFalse(manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), "clearAllChangeMarks left a field dirty");

        var definition = manager.getFieldDefinition("synced");
        h.assertTrue(definition != null, "definition lookup failed");
        manager.markFieldsForSync(definition);
        h.assertTrue(manager.updateFieldDirtyFlags(LogicalSide.SERVER, true), "markFieldsForSync(definition) had no effect");

        manager.markAsChanged();
        h.assertTrue(manager.isChanged(), "markAsChanged had no effect");
        manager.clearChanged();
        h.assertFalse(manager.isChanged(), "clearChanged had no effect");

        h.assertTrue(throwsIllegalArgument(() -> manager.markFieldsForSync("nope")),
                "unknown field name did not throw");
        h.assertTrue(throwsIllegalArgument(() -> manager.writeFieldToData("nope")),
                "unknown field name did not throw on writeFieldToData");
        h.succeed();
    }

    /**
     * Proves the real send path: {@code DataSyncNetwork.syncBlockEntityToClient(be, false, true)} runs
     * against a block entity that is genuinely placed in the level and consumes the pending sync flags
     * of the fields it serialized.
     *
     * <p>A GameTest server has no tracking player, so nothing receives the payload; what is asserted
     * here is the send path itself — it must not throw, and after it has run the incremental dirty
     * state must be gone (a following incremental write has nothing left to send). The block entity is
     * placed first, mutated, and primed through {@link #prime} so the baseline is clean; the pending
     * flags the send path has to consume are then produced by one more change. The assertion runs a
     * couple of ticks later because the helper hands the work to the server executor.</p>
     */
    @GameTest(template = "empty", batch = "network", timeoutTicks = 60)
    public static void networkSendPathConsumesPendingSyncFlags(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        TestBlockEntity testBlockEntity = placeBlockEntity(h, BlockPos.ZERO);
        mutate(testBlockEntity);
        prime(testBlockEntity, context);
        // A genuine change after the baseline, so the send path has something pending to consume.
        testBlockEntity.synced = 4711;
        h.assertTrue(testBlockEntity.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                "the placed block entity had no pending changes");

        try {
            DataSyncNetwork.syncBlockEntityToClient(testBlockEntity, false, true);
        } catch (Throwable t) {
            h.fail("syncBlockEntityToClient threw " + t);
            return;
        }

        h.runAfterDelay(2, () -> {
            h.assertFalse(testBlockEntity.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                    "pending sync flags were not consumed by the send path");
            h.assertValueEqual(testBlockEntity.getFieldDataManager()
                            .writeToNetworkBuffer(LogicalSide.SERVER, context, false).length, 0,
                    "incremental write after the send path was not empty");
            h.setBlock(BlockPos.ZERO, Blocks.AIR);
            h.succeed();
        });
    }

    /**
     * Proves that {@code @SaveToDisk(skipWhen)} and {@code @SaveToDisk(defaultValue)} keep fields out
     * of the written {@code field_save} map, and that flipping the condition makes them appear.
     *
     * <p>The suite drives the data side directly ({@code writeToData()}) and then re-reads the same
     * bytes through the block-entity save hook, so both layers are covered. Each attribute is checked
     * from both sides: {@code withDefault == 7} matches the configured default and {@code skipped = -1}
     * makes the predicate fire (both hidden), while {@code withDefault = 8} and {@code skipped = 1}
     * (predicate false) are written.</p>
     */
    @GameTest(template = "empty", batch = "disk")
    public static void skipPredicatesAndDefaultsHideFields(GameTestHelper h) {
        var lookup = h.getLevel().registryAccess();
        try (var ignored = RegistryContext.use(lookup)) {
            TestBlockEntity defaults = newEntity();
            defaults.skipped = -1; // skipMe(-1) is true → hidden, exactly like the default value below
            Data saved = defaults.getFieldDataManager().writeToData();
            h.assertTrue(saved instanceof StringMapData, "writeToData did not return a StringMapData");
            if (saved instanceof StringMapData map) {
                h.assertFalse(map.containsKey("withDefault"), "default-valued field was written");
                h.assertFalse(map.containsKey("skipped"), "skipWhen-skipped field was written");
                h.assertTrue(map.containsKey("i"), "unannotated-default field was skipped");
            }

            TestBlockEntity flipped = newEntity();
            flipped.withDefault = 8;
            flipped.skipped = 1;
            h.assertTrue(flipped.skipMe(-1), "skip predicate did not fire for a negative value");
            h.assertFalse(flipped.skipMe(1), "skip predicate fired for a positive value");

            Data flippedData = flipped.getFieldDataManager().writeToData();
            h.assertTrue(flippedData instanceof StringMapData, "writeToData did not return a StringMapData");
            if (flippedData instanceof StringMapData map) {
                h.assertTrue(map.containsKey("withDefault"), "non-default value was not written");
                h.assertTrue(map.containsKey("skipped"), "value the predicate accepts was not written");
            }

            // The same rules hold for the byte payload the block entity stores: a skip-triggering value
            // stays out, a value the predicate accepts is written.
            CompoundTag tag = flipped.saveToTag(lookup);
            if (Data.readData(tag.getByteArray("field_save")) instanceof StringMapData map) {
                h.assertTrue(map.containsKey("withDefault"), "non-default value missing from field_save");
                h.assertTrue(map.containsKey("skipped"), "non-skipped value missing from field_save");
            } else {
                h.fail("field_save did not decode to a StringMapData");
            }

            TestBlockEntity skipped = newEntity();
            skipped.withDefault = 7;
            skipped.skipped = -1;
            CompoundTag skippedTag = skipped.saveToTag(lookup);
            if (Data.readData(skippedTag.getByteArray("field_save")) instanceof StringMapData map) {
                h.assertFalse(map.containsKey("withDefault"), "default value present in field_save");
                h.assertFalse(map.containsKey("skipped"), "skipWhen-skipped value present in field_save");
            } else {
                h.fail("field_save did not decode to a StringMapData");
            }
        }
        h.succeed();
    }

    /**
     * Proves the codec surface: lookups, data-side round trips, stream-side round trips and the
     * {@code Data} byte format.
     *
     * <p>{@code DataSyncCodec.get(...)} resolves {@code ItemStack}, {@code FluidStack},
     * {@code Component}, an enum, {@code int[]}, {@code GlobalPos}, {@code SectionPos} and
     * {@code Vec3i}; a couple of values are round-tripped through {@code dataWriter/dataReader}
     * directly, a couple more through {@code streamWriter/streamReader} on a
     * {@link RegistryFriendlyByteBuf} built from {@link SyncContext#buffer(ByteBuf)}, and one
     * {@link Data} tree goes through {@code Data.writeData}/{@code Data.readData} on an
     * {@code Unpooled} buffer that is released in a {@code finally} block.</p>
     *
     * <p>The Holder variants are resolved through their generic key — {@code (Holder.class, elementType)}
     * — and round-tripped on both paths: the static-registry {@code Holder<Item>} and the data-driven
     * {@code Holder<Enchantment>}, whose disk decode runs inside {@link RegistryContext#use} because the
     * enchantment registry has no static {@code Registry} to look the key up in.</p>
     *
     * <p>Also covered here: {@link NbtUtil#convertToData(net.minecraft.nbt.Tag)} /
     * {@link NbtUtil#convertToTag(Data)} on a nested {@code CompoundTag}; the
     * {@link CombinedCodec#list}/{@link CombinedCodec#set}/{@link CombinedCodec#map}/
     * {@link CombinedCodec#collection}/{@link CombinedCodec#array} factories built from
     * {@code STRING_CODEC}/{@code INT_CODEC}, each on both paths; the positive path of the single-field
     * API ({@code writeFieldToData} → {@code readFieldFromData}); the
     * {@code markFieldsForSync(DataFieldDefinition)} overload; and the
     * {@code Registry.streamCodec()}/{@code dataCodec()}/{@code combinedCodec()} accessors of a frozen
     * registry.</p>
     */
    @GameTest(template = "empty", batch = "codec")
    public static void codecLookupsAndByteRoundTripsSucceed(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        try (var ignored = RegistryContext.use(h.getLevel().registryAccess())) {
            h.assertTrue(DataSyncCodec.get(ItemStack.class) != null, "no ItemStack codec");
            h.assertTrue(DataSyncCodec.get(FluidStack.class) != null, "no FluidStack codec");
            h.assertTrue(DataSyncCodec.get(Component.class) != null, "no Component codec");
            h.assertTrue(DataSyncCodec.get(Direction.class) != null, "no enum codec");
            h.assertTrue(DataSyncCodec.get(int[].class) != null, "no int[] codec");
            h.assertTrue(DataSyncCodec.get(GlobalPos.class) != null, "no GlobalPos codec");
            h.assertTrue(DataSyncCodec.get(SectionPos.class) != null, "no SectionPos codec");
            h.assertTrue(DataSyncCodec.get(Vec3i.class) != null, "no Vec3i codec");
            // Holder variants live under the generic lookup key (Holder.class, elementType).
            h.assertTrue(DataSyncCodec.get(Holder.class, Item.class) != null, "no Holder<Item> codec");
            h.assertTrue(DataSyncCodec.get(Holder.class, Enchantment.class) != null, "no Holder<Enchantment> codec");

            // Data side: hand-written codecs and the auto-generated array codec.
            DataSyncCodec<GlobalPos> globalPosCodec = DataSyncCodec.get(GlobalPos.class);
            GlobalPos globalPos = GlobalPos.of(Level.NETHER, new BlockPos(4, 5, 6));
            h.assertValueEqual(globalPosCodec.dataReader.decode(globalPosCodec.dataWriter.encode(globalPos), 0),
                    globalPos, "GlobalPos data round trip");
            DataSyncCodec<SectionPos> sectionPosCodec = DataSyncCodec.get(SectionPos.class);
            SectionPos sectionPos = SectionPos.of(7, 8, 9);
            h.assertValueEqual(sectionPosCodec.dataReader.decode(sectionPosCodec.dataWriter.encode(sectionPos), 0),
                    sectionPos, "SectionPos data round trip");
            DataSyncCodec<String[]> stringArrayCodec = DataSyncCodec.get(String[].class);
            String[] values = {"a", "b", "c"};
            String[] decoded = stringArrayCodec.dataReader.decode(stringArrayCodec.dataWriter.encode(values), 0);
            h.assertTrue(Arrays.equals(values, decoded), "String[] data round trip");
            // Nullable slots are the array *field* path's job: ArrayAccess writes a presence boolean per
            // element, which the disk/chunk-load tests cover through the uuids/stacks/tanks fields. The
            // standalone object-array codec keeps its compact per-element format and requires non-null
            // elements.

            // Stream side through a registry-aware buffer.
            DataSyncCodec<Vec3i> vec3iCodec = DataSyncCodec.get(Vec3i.class);
            Vec3i vec = new Vec3i(-1, 2, -3);
            ByteBuf streamBytes = Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf wrapper = context.buffer(streamBytes);
                vec3iCodec.streamWriter.encode(wrapper, vec);
                h.assertValueEqual(vec3iCodec.streamReader.decode(wrapper), vec, "Vec3i stream round trip");

                ItemStack stack = new ItemStack(Items.DIAMOND, 3);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("codec-proof"));
                DataSyncCodec<ItemStack> stackCodec = DataSyncCodec.get(ItemStack.class);
                stackCodec.streamWriter.encode(wrapper, stack);
                h.assertTrue(ItemStack.matches(stack, stackCodec.streamReader.decode(wrapper)),
                        "ItemStack stream round trip lost components");

                FluidStack fluid = new FluidStack(Fluids.WATER, 1234);
                fluid.set(DataComponents.CUSTOM_NAME, Component.literal("fluid-codec-proof"));
                DataSyncCodec<FluidStack> fluidCodec = DataSyncCodec.get(FluidStack.class);
                fluidCodec.streamWriter.encode(wrapper, fluid);
                h.assertTrue(FluidStack.matches(fluid, fluidCodec.streamReader.decode(wrapper)),
                        "FluidStack stream round trip lost components");
            } finally {
                streamBytes.release();
            }

            // Holder codecs: the static-registry form (VarInt registry id on the wire, registry key on
            // disk) and the data-driven form (registry key on both paths; its disk decode runs behind
            // RegistryContext because a dynamic registry has no static Registry to look the key up in).
            DataSyncCodec<Holder<Item>> itemHolderCodec = DataSyncCodec.get(Holder.class, Item.class);
            Holder<Item> heldItem = BuiltInRegistries.ITEM.wrapAsHolder(Items.GOLD_INGOT);
            h.assertValueEqual(itemHolderCodec.dataReader.decode(itemHolderCodec.dataWriter.encode(heldItem), 0).value(),
                    Items.GOLD_INGOT, "Holder<Item> data round trip");

            DataSyncCodec<Holder<Enchantment>> enchantmentHolderCodec = DataSyncCodec.get(Holder.class, Enchantment.class);
            Holder<Enchantment> sharpness = h.getLevel().registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SHARPNESS);
            Holder<Enchantment> decodedEnchantment;
            try (var diskContext = RegistryContext.use(h.getLevel().registryAccess())) {
                decodedEnchantment = enchantmentHolderCodec.dataReader
                        .decode(enchantmentHolderCodec.dataWriter.encode(sharpness), 0);
            }
            h.assertValueEqual(decodedEnchantment.value(), sharpness.value(), "Holder<Enchantment> data round trip");

            // Both Holder forms on one registry-aware buffer, released in a finally block.
            ByteBuf holderBytes = Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf holderWrapper = context.buffer(holderBytes);
                itemHolderCodec.streamWriter.encode(holderWrapper, heldItem);
                h.assertValueEqual(itemHolderCodec.streamReader.decode(holderWrapper).value(), Items.GOLD_INGOT,
                        "Holder<Item> stream round trip");
                enchantmentHolderCodec.streamWriter.encode(holderWrapper, sharpness);
                h.assertValueEqual(enchantmentHolderCodec.streamReader.decode(holderWrapper).value(), sharpness.value(),
                        "Holder<Enchantment> stream round trip");
            } finally {
                holderBytes.release();
            }

            // Data byte format, released in a finally block.
            Data tree = StringMapData.EMPTY.shallowCopy();
            h.assertTrue(tree instanceof StringMapData, "StringMapData copy is not a StringMapData");
            if (tree instanceof StringMapData map) {
                map.putInt("answer", 42);
                map.putString("name", "datasynclib");
                ByteBuf buffer = Unpooled.buffer();
                try {
                    Data.writeData(buffer, tree);
                    Data decodedTree = Data.readData(buffer);
                    h.assertTrue(decodedTree instanceof StringMapData, "Data byte round trip changed the type");
                    if (decodedTree instanceof StringMapData decodedMap) {
                        h.assertValueEqual(decodedMap.getInt("answer"), 42, "Data byte round trip int");
                        h.assertValueEqual(decodedMap.getString("name"), "datasynclib", "Data byte round trip string");
                    }
                } finally {
                    buffer.release();
                }
            }

            // NbtUtil: a nested CompoundTag (a list, an int array and a nested compound) survives the
            // conversion into the Data type system and back.
            CompoundTag nbtSource = new CompoundTag();
            ListTag nbtList = new ListTag();
            nbtList.add(IntTag.valueOf(3));
            nbtList.add(IntTag.valueOf(4));
            nbtSource.put("list", nbtList);
            nbtSource.putIntArray("array", new int[]{5, 6, 7});
            CompoundTag nbtNested = new CompoundTag();
            nbtNested.putString("name", "inner");
            nbtNested.putBoolean("flag", true);
            nbtSource.put("nested", nbtNested);
            Tag nbtBack = NbtUtil.convertToTag(NbtUtil.convertToData(nbtSource));
            h.assertTrue(nbtBack instanceof CompoundTag, "NbtUtil round trip did not produce a CompoundTag");
            if (nbtBack instanceof CompoundTag back) {
                h.assertTrue(nbtEquals(nbtSource, back), "NbtUtil nested CompoundTag round trip");
            }

            // CombinedCodec container factories, data side (the disk layout of each container).
            DataSyncCodec<List<String>> listCodec = CombinedCodec.list(DataSyncCodec.STRING_CODEC);
            h.assertValueEqual(listCodec.dataReader.decode(listCodec.dataWriter.encode(List.of("a", "b")), 0),
                    List.of("a", "b"), "CombinedCodec.list data round trip");
            DataSyncCodec<Set<String>> setCodec = CombinedCodec.set(DataSyncCodec.STRING_CODEC);
            Set<String> decodedSet = setCodec.dataReader.decode(setCodec.dataWriter.encode(Set.of("a", "b")), 0);
            h.assertValueEqual(decodedSet.size(), 2, "CombinedCodec.set data round trip size");
            DataSyncCodec<Map<String, Integer>> mapCodec =
                    CombinedCodec.map(HashMap::new, DataSyncCodec.STRING_CODEC, DataSyncCodec.INT_CODEC);
            h.assertValueEqual(mapCodec.dataReader.decode(mapCodec.dataWriter.encode(Map.of("a", 1)), 0),
                    Map.of("a", 1), "CombinedCodec.map data round trip");
            DataSyncCodec<Collection<String>> collectionCodec =
                    CombinedCodec.collection(ArrayList::new, DataSyncCodec.STRING_CODEC);
            h.assertValueEqual(new ArrayList<>(collectionCodec.dataReader
                            .decode(collectionCodec.dataWriter.encode(new ArrayList<>(List.of("a"))), 0)),
                    List.of("a"), "CombinedCodec.collection data round trip");
            DataSyncCodec<String[]> containerArrayCodec = CombinedCodec.array(String.class, DataSyncCodec.STRING_CODEC);
            h.assertTrue(Arrays.equals(new String[]{"a", "b"}, containerArrayCodec.dataReader
                            .decode(containerArrayCodec.dataWriter.encode(new String[]{"a", "b"}), 0)),
                    "CombinedCodec.array data round trip");

            // The same factories on the stream side, through one registry-aware buffer released in a
            // finally block.
            ByteBuf containerBytes = Unpooled.buffer();
            try {
                RegistryFriendlyByteBuf containerWrapper = context.buffer(containerBytes);
                listCodec.streamWriter.encode(containerWrapper, List.of("a", "b"));
                h.assertValueEqual(listCodec.streamReader.decode(containerWrapper), List.of("a", "b"),
                        "CombinedCodec.list stream round trip");
                setCodec.streamWriter.encode(containerWrapper, Set.of("a", "b"));
                h.assertValueEqual(setCodec.streamReader.decode(containerWrapper).size(), 2,
                        "CombinedCodec.set stream round trip size");
                mapCodec.streamWriter.encode(containerWrapper, Map.of("a", 1));
                h.assertValueEqual(mapCodec.streamReader.decode(containerWrapper), Map.of("a", 1),
                        "CombinedCodec.map stream round trip");
                collectionCodec.streamWriter.encode(containerWrapper, new ArrayList<>(List.of("a")));
                h.assertValueEqual(new ArrayList<>(collectionCodec.streamReader.decode(containerWrapper)), List.of("a"),
                        "CombinedCodec.collection stream round trip");
                containerArrayCodec.streamWriter.encode(containerWrapper, new String[]{"a", "b"});
                h.assertTrue(Arrays.equals(new String[]{"a", "b"},
                                containerArrayCodec.streamReader.decode(containerWrapper)),
                        "CombinedCodec.array stream round trip");
            } finally {
                containerBytes.release();
            }

            // Single-field API positive path: one field written out and read back on its own.
            TestBlockEntity singleSource = newEntity();
            singleSource.i = 777;
            Data singleField = singleSource.getFieldDataManager().writeFieldToData("i");
            h.assertFalse(singleField.isNone(), "writeFieldToData returned an empty payload");
            TestBlockEntity singleTarget = newEntity();
            singleTarget.i = 0;
            singleTarget.getFieldDataManager().readFieldFromData(singleField, FieldDataHolderBlockEntity.VERSION, "i");
            h.assertValueEqual(singleTarget.i, 777, "readFieldFromData did not restore the field");

            // markFieldsForSync(DataFieldDefinition): the definition overload — not just the name
            // overload — is what makes a field with autoDetect = false travel.
            TestBlockEntity marked = mutate(newEntity());
            FieldDataManager markedManager = marked.getFieldDataManager();
            prime(marked, context);
            marked.manual = 1234;
            markedManager.updateFieldDirtyFlags(LogicalSide.SERVER, true);
            h.assertValueEqual(markedManager.writeToNetworkBuffer(LogicalSide.SERVER, context, false).length, 0,
                    "autoDetect = false field was auto-detected");
            h.assertTrue(markedManager.getFieldDefinition("manual") != null, "definition lookup by name failed");
            markedManager.markFieldsForSync(markedManager.getFieldDefinition("manual"));
            byte[] definitionMarked = markedManager.writeToNetworkBuffer(LogicalSide.SERVER, context, false);
            h.assertTrue(definitionMarked.length > 0, "markFieldsForSync(definition) had no effect");
            TestBlockEntity definitionTarget = newEntity();
            clear(definitionTarget);
            definitionTarget.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, definitionMarked);
            h.assertValueEqual(definitionTarget.manual, 1234, "definition-marked field was not applied");

            // Registry accessors: a frozen test registry exposes the stream half, the data half and the
            // combined codec built from them.
            record CodecTag(String name, int value) {
            }
            Registry<String, CodecTag> codecRegistry =
                    new Registry<>("datasynclib:codec_tag", DataCodec.STRING_CODEC, CodecTag::name, CodecTag.class);
            codecRegistry.unfreeze();
            codecRegistry.register("one", new CodecTag("one", 1));
            codecRegistry.register("two", new CodecTag("two", 2));
            codecRegistry.freeze();
            h.assertTrue(codecRegistry.streamCodec() != null, "Registry.streamCodec() was null");
            h.assertTrue(codecRegistry.dataCodec() != null, "Registry.dataCodec() was null");
            h.assertTrue(codecRegistry.combinedCodec() != null, "Registry.combinedCodec() was null");
            h.assertValueEqual(codecRegistry.combinedCodec().dataReader
                            .decode(codecRegistry.combinedCodec().dataWriter.encode(new CodecTag("two", 2)), 0).value(), 2,
                    "Registry.combinedCodec() data round trip");

            // The annotated field codec (@Codec) is not part of the global registry.
            h.assertTrue(DataSyncCodec.get(TestBlockEntity.A.class) == null,
                    "@Codec codec leaked into the global registry");
        }
        h.succeed();
    }

    /**
     * Proves the registry/enum/strategy helpers.
     *
     * <p>{@link EnumUtil#isFixed(Class)} distinguishes ordinal-persisted enums from name-persisted
     * ones, {@code getEnum}/{@code getSerializedName}/{@code getSerializedEnum} agree with the
     * constants, a {@link Registry} that is unfrozen, filled and frozen registers its codec globally
     * for its value type, and the registered {@link ItemStackHashStrategy}/
     * {@link ItemStackArrayHashStrategy} detect a stack edited in place with {@code setCount} — both on
     * a scalar {@code ItemStack} field and on an {@code ItemStack[]} field, which is exactly what an
     * unregistered array would miss.</p>
     */
    @GameTest(template = "empty", batch = "helpers")
    public static void registryEnumAndStrategyHelpersWork(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        // Fixed enums are persisted by ordinal.
        h.assertTrue(EnumUtil.isFixed(LogicalSide.class), "LogicalSide is not a fixed enum");
        h.assertTrue(EnumUtil.isFixed(Direction.class), "Direction is not a fixed enum");
        h.assertFalse(EnumUtil.isFixed(Direction.AxisDirection.class), "AxisDirection should not be fixed");
        h.assertValueEqual(EnumUtil.getEnum(Direction.class, "NORTH"), Direction.NORTH, "getEnum");
        h.assertValueEqual(EnumUtil.getSerializedName(Direction.NORTH), "north", "getSerializedName");
        h.assertValueEqual(EnumUtil.getSerializedEnum(Direction.class, "north"), Direction.NORTH, "getSerializedEnum");
        h.assertTrue(EnumUtil.getEnum(Direction.class, "SIDEWAYS") == null, "unknown enum name resolved");

        // A frozen registry registers its codec globally for its value type.
        Registry<String, GameTestTag> registry =
                new Registry<>("datasynclib:gametest_tag", DataCodec.STRING_CODEC, GameTestTag::name, GameTestTag.class);
        registry.unfreeze();
        registry.register("one", new GameTestTag("one", 1));
        registry.register("two", new GameTestTag("two", 2));
        registry.freeze();
        h.assertTrue(registry.isFrozen(), "registry did not freeze");
        h.assertValueEqual(registry.values().size(), 2, "registry size");
        h.assertValueEqual(registry.get("two").value(), 2, "registry key lookup");
        // The registry exposes both halves plus the combined codec built from them.
        h.assertTrue(registry.streamCodec() != null, "registry streamCodec missing");
        h.assertTrue(registry.dataCodec() != null, "registry dataCodec missing");
        h.assertTrue(registry.combinedCodec() != null, "registry combinedCodec missing");

        DataSyncCodec<GameTestTag> globalCodec = DataSyncCodec.get(GameTestTag.class);
        h.assertTrue(globalCodec != null, "frozen registry did not register a global codec");
        if (globalCodec != null) {
            GameTestTag tag = new GameTestTag("two", 2);
            h.assertValueEqual(globalCodec.dataReader.decode(globalCodec.dataWriter.encode(tag), 0).value(), 2,
                    "global registry codec round trip");
        }

        // Strategy levels: only the count differs.
        ItemStack one = new ItemStack(Items.IRON_INGOT, 1);
        ItemStack four = new ItemStack(Items.IRON_INGOT, 4);
        h.assertFalse(ItemStackHashStrategy.ALL.equals(one, four), "ALL ignored the count");
        h.assertTrue(ItemStackHashStrategy.ITEM.equals(one, four), "ITEM compared the count");
        h.assertFalse(ItemStackArrayHashStrategy.ALL.equals(new ItemStack[]{one}, new ItemStack[]{four}),
                "array ALL ignored the count");
        h.assertFalse(FluidStackArrayHashStrategy.ALL.equals(new FluidStack[]{new FluidStack(Fluids.WATER, 1)},
                new FluidStack[]{new FluidStack(Fluids.WATER, 4)}), "fluid array ALL ignored the amount");
        h.assertTrue(FluidStackHashStrategy.FLUID.equals(new FluidStack(Fluids.WATER, 1), new FluidStack(Fluids.WATER, 4)),
                "FLUID compared the amount");

        // An in-place setCount must be seen by the scalar ItemStack field ...
        TestBlockEntity scalar = newEntity();
        scalar.stack = new ItemStack(Items.IRON_INGOT, 1);
        prime(scalar, context);
        scalar.stack.setCount(2);
        h.assertTrue(scalar.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                "in-place setCount was not detected on the scalar ItemStack field");

        // ... and by the ItemStack[] field, whose registered array strategy compares slot contents.
        TestBlockEntity array = newEntity();
        array.stacks[0] = new ItemStack(Items.IRON_INGOT, 1);
        array.stacks[1] = new ItemStack(Items.GOLD_INGOT, 1);
        prime(array, context);
        array.stacks[0].setCount(5);
        h.assertTrue(array.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                "in-place setCount was not detected on the ItemStack[] field");

        // A replaced slot is still detected after the mark was consumed.
        prime(array, context);
        array.stacks[1] = new ItemStack(Items.DIAMOND, 2);
        h.assertTrue(array.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                "replaced ItemStack[] slot was not detected");

        // The scalar FluidStack strategy sees an in-place amount change as well.
        TestBlockEntity fluidHolder = newEntity();
        fluidHolder.fluid = new FluidStack(Fluids.WATER, 1000);
        prime(fluidHolder, context);
        fluidHolder.fluid.setAmount(1200);
        h.assertTrue(fluidHolder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true),
                "in-place setAmount was not detected on the FluidStack field");

        // Codec lookup is an exact-class match: a subtype without its own registration resolves to
        // null even though its supertype has a codec.
        h.assertTrue(DataSyncCodec.get(MutableComponent.class) == null, "unregistered subtype resolved a codec");
        h.assertTrue(DataSyncCodec.get(Component.class) != null, "Component codec disappeared");
        h.succeed();
    }

    /**
     * Proves the entity path: a real {@link TestEntity} spawned into the level persists and
     * synchronizes its {@code IFieldDataHolder} state.
     *
     * <p>The entity is created through {@code h.spawn(ModEntityTypes.TEST_ENTITY.get(), ...)}, so it
     * goes through {@code EntityType.create} exactly as the game does. Its disk path is driven through
     * {@code addAdditionalSaveData}/{@code readAdditionalSaveData} (the manager's {@code Data} payload
     * the entity writes under {@code dsl_data}), and its network path through
     * {@code writeToNetworkBuffer}/{@code readFromNetworkBuffer} on detached entities. Both the flat
     * fields and the {@code @AdditionalHolder(childManager = true)} sub-object must survive.</p>
     */
    @GameTest(template = "empty", batch = "entity")
    public static void entityDiskAndNetworkPathsWork(GameTestHelper h) {
        var context = SyncContext.neoforge(h.getLevel().registryAccess());
        try (var ignored = RegistryContext.use(h.getLevel().registryAccess())) {
            TestEntity spawned = h.spawn(ModEntityTypes.TEST_ENTITY.get(), BlockPos.ZERO);
            h.assertTrue(spawned != null, "TestEntity was not spawned");

            CompoundTag tag = new CompoundTag();
            spawned.addAdditionalSaveData(tag);
            h.assertTrue(tag.contains("dsl_data"), "entity did not write its field payload");
            h.assertTrue(tag.getByteArray("dsl_data").length > 0, "entity field payload was empty");

            TestEntity restored = new TestEntity(ModEntityTypes.TEST_ENTITY.get(), h.getLevel());
            restored.readAdditionalSaveData(tag);
            h.assertTrue(restored.getFieldDataManager().getFieldDefinition("syncTicks") != null,
                    "restored entity lost its definitions");
            h.assertTrue(restored.getFieldDataManager().getFieldDefinition("sub") != null,
                    "restored entity lost the child-manager field");

            // A field with no entry in the payload must stay untouched: loading a foreign tag is a no-op.
            CompoundTag unrelated = new CompoundTag();
            unrelated.putInt("whatever", 1);
            TestEntity untouched = new TestEntity(ModEntityTypes.TEST_ENTITY.get(), h.getLevel());
            untouched.readAdditionalSaveData(unrelated);
            h.assertValueEqual(Arrays.equals(
                            untouched.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true),
                            new TestEntity(ModEntityTypes.TEST_ENTITY.get(), h.getLevel())
                                    .getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true)),
                    true, "loading an unrelated tag changed the entity state");

            // The fields themselves are private, so the disk round trip is proven through the synced
            // (and persisted) serialized form: both entities must produce the same network payload,
            // and a receiver must decode it without error.
            byte[] sourceBytes = spawned.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
            byte[] restoredBytes = restored.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
            h.assertTrue(sourceBytes.length > 0, "entity source produced an empty network write");
            h.assertTrue(Arrays.equals(sourceBytes, restoredBytes),
                    "entity disk round trip changed the synced state");

            TestEntity receiver = new TestEntity(ModEntityTypes.TEST_ENTITY.get(), h.getLevel());
            receiver.getFieldDataManager().readFromNetworkBuffer(LogicalSide.CLIENT, context, sourceBytes);
            h.assertTrue(Arrays.equals(
                            receiver.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true),
                            sourceBytes),
                    "entity network round trip did not reproduce the source state");

            // A full write is stable: writing twice from the same entity yields the same bytes.
            byte[] first = spawned.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
            byte[] second = spawned.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, true);
            h.assertTrue(Arrays.equals(first, second), "entity full network write was not stable");

            spawned.discard();
        }
        h.succeed();
    }

    /**
     * Proves that the level-free development suites really run inside this server and stay clean.
     *
     * <p>{@link TestBlockEntityTests} (and {@link DataSyncSelfTests}) are started once per JVM from
     * {@link TestBlockEntity#serverTick}, i.e. from the ticker of a <em>placed, ticking</em>
     * {@code TestBlockEntity} — the one path the GameTests themselves do not take, because they call
     * the framework API directly on detached instances. This test therefore places the test block,
     * lets the level tick it a few times and then asserts what the suite reported, which turns a
     * failure of that suite into a failing GameTest instead of a log line nobody gates on.</p>
     */
    @GameTest(template = "empty", batch = "devsuite", timeoutTicks = 60)
    public static void devSuiteRunFromTheTickPathReportsClean(GameTestHelper h) {
        h.setBlock(BlockPos.ZERO, ModBlocks.TEST_BLOCK.get());
        h.assertTrue(h.getBlockEntity(BlockPos.ZERO) != null, "placed test block entity is missing");
        // The block is ticked by the level itself; by this tick the once-per-JVM suite start has run.
        h.runAtTickTime(3, () -> {
            h.assertTrue(TestBlockEntity.selfTestsRun, "the placed TestBlockEntity never ticked");
            h.assertTrue(TestBlockEntityTests.lastChecks() > 0, "the dev suite executed no checks");
            h.assertValueEqual(TestBlockEntityTests.lastFailures(), 0, "TestBlockEntityTests failures");
            h.assertValueEqual(DataSyncSelfTests.lastFailures(), 0, "DataSyncSelfTests failures");
            h.succeed();
        });
    }

    // ==================== helpers ====================

    /**
     * Builds a fresh block entity without a level. The block entity type still comes from the game
     * registry, so this only works inside a running server — which is the point of a GameTest.
     */
    private static TestBlockEntity newEntity() {
        // 1.21 validates the state passed to a block entity against the states its type is registered
        // for, so a stand-in state such as Blocks.IRON_BLOCK throws "Invalid block entity ...".
        return new TestBlockEntity(BlockPos.ZERO, ModBlocks.TEST_BLOCK.get().defaultBlockState());
    }

    /**
     * Places a real {@code TestBlockEntity} in the level at the given relative position and returns it.
     *
     * <p>This is the one path the level-free suites cannot cover: the block, the block entity type and
     * the passed-in block state all come from the live server registries. The position is cleared
     * first, so a test never inherits the block entity a previous test (or a previous batch) left
     * behind at the same coordinates.</p>
     *
     * @throws net.minecraft.gametest.framework.GameTestAssertException if the block entity is missing
     */
    private static TestBlockEntity placeBlockEntity(GameTestHelper h, BlockPos relativePos) {
        h.setBlock(relativePos, Blocks.AIR);
        h.setBlock(relativePos, ModBlocks.TEST_BLOCK.get());
        TestBlockEntity be = h.getBlockEntity(relativePos);
        h.assertTrue(be != null, "placed test block entity is missing");
        return be;
    }

    /**
     * Gives every managed field family a non-default value so a round trip has something to carry.
     *
     * <p>The scalar {@code ItemStack}/{@code FluidStack} also get a {@code CUSTOM_NAME} data component,
     * so every round-trip assertion proves that 1.21 components survive the codecs — which is exactly
     * what {@code ItemStack.matches}/{@code FluidStack.matches} compare.</p>
     */
    private static TestBlockEntity mutate(TestBlockEntity be) {
        be.i = 1234;
        for (int i = 0; i < be.uuids.length; i++) {
            be.uuids[i] = "uuid-" + i;
        }
        be.uuids[1] = null; // a null element must survive the array codec
        be.stacks[0] = new ItemStack(Items.DIAMOND, 5);
        be.stacks[1] = new ItemStack(Items.GOLD_INGOT, 2);
        be.stacks[2] = null; // "no item here"
        be.stack = new ItemStack(Items.GOLD_INGOT, 3);
        be.stack.set(DataComponents.CUSTOM_NAME, Component.literal("stack-proof"));
        be.fluid = new FluidStack(Fluids.LAVA, 750);
        be.fluid.set(DataComponents.CUSTOM_NAME, Component.literal("fluid-proof"));
        be.heldItem = BuiltInRegistries.ITEM.wrapAsHolder(Items.GOLD_INGOT);
        be.tanks[0] = new FluidStack(Fluids.LAVA, 250);
        be.tanks[1] = new FluidStack(Fluids.WATER, 4000);
        be.tanks[2] = null;
        be.directions[0][0] = Direction.UP;
        be.directions[2][1] = Direction.WEST;
        be.uuidSet.clear();
        be.uuidSet.add("set-a");
        be.uuidSet.add("set-b");
        be.map.clear();
        be.map.put(9, true);
        be.map.put(10, false);
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
        // instanceAsValue: a non-null instance (contents included) must round-trip on both paths.
        be.optionalInts = new IntArrayList(new int[]{1, 2});
        be.object2IntMap.clear();
        be.object2IntMap.put("x", 42);
        be.handler.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 7));
        be.handlerArray[0].setStackInSlot(1, new ItemStack(Items.EMERALD, 4));
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
        be.manual = 17;
        // A skip-triggering value (the negative branch of skipMe), so the disk round trip below proves
        // the skipWhen field really stays out of the written map.
        be.skipped = -3;
        be.tagData.putInt("aaa", 99);
        be.tagData.putString("bbb", "ccc");
        // The writable @Conversion field, restored through its toField function on both paths.
        be.converted = new TestBlockEntity.C(11, 22);
        // savedEmpty is left all-null on purpose: the point of that field is the saveEmpty attribute.
        return be;
    }

    /**
     * Resets the persisted/synced state of a freshly built entity so restoration is provable — a value
     * that was never overwritten would otherwise look like a successful round trip. Container fields
     * are emptied in place rather than nulled, because the access layer works on the live container.
     */
    private static void clear(TestBlockEntity be) {
        be.i = 0;
        Arrays.fill(be.uuids, null);
        Arrays.fill(be.stacks, null);
        Arrays.fill(be.savedEmpty, null);
        be.stack = ItemStack.EMPTY;
        Arrays.fill(be.tanks, null);
        be.fluid = FluidStack.EMPTY;
        be.heldItem = BuiltInRegistries.ITEM.wrapAsHolder(Items.AIR);
        for (Direction[] row : be.directions) {
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
        be.optionalInts = null;
        be.handler.setStackInSlot(0, ItemStack.EMPTY);
        be.handlerArray[0].setStackInSlot(1, ItemStack.EMPTY);
        be.handlerArray[1].setStackInSlot(1, ItemStack.EMPTY);
        be.module.ticks = 0;
        be.module.name = "";
        be.module.values.clear();
        be.globalPos = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
        be.sectionPos = SectionPos.of(0, 0, 0);
        be.vec3i = Vec3i.ZERO;
        be.loaded = 0;
        be.loadedListenerCalls = 0;
        be.lastLoaded = -1;
        be.synced = 0;
        be.manual = 0;
        be.tagData.getAllKeys().clear();
        be.converted = new TestBlockEntity.C(0, 0);
    }

    /**
     * Marks everything dirty and writes it once, so the next assertion starts from a clean baseline.
     * A brand-new entity reports every field as changed on its first check, which would otherwise make
     * a later "was this change detected?" assertion pass for the wrong reason.
     */
    private static void prime(TestBlockEntity be, SyncContext context) {
        be.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, true);
        be.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, context, false);
    }

    /** Asserts every {@code @SaveToDisk} family of a disk round trip. */
    private static void expectSaved(GameTestHelper h, String prefix, TestBlockEntity source, TestBlockEntity restored) {
        h.assertValueEqual(restored.i, source.i, prefix + ".i");
        h.assertTrue(Arrays.equals(source.uuids, restored.uuids), prefix + ".uuids (nulls included)");
        h.assertTrue(stacksEqual(source.stacks, restored.stacks), prefix + ".stacks");
        h.assertTrue(ItemStack.matches(source.stack, restored.stack), prefix + ".stack components");
        h.assertTrue(fluidsEqual(source.fluid, restored.fluid), prefix + ".fluid");
        h.assertValueEqual(restored.heldItem.value(), source.heldItem.value(), prefix + ".heldItem (Holder<Item>)");
        h.assertTrue(fluidsEqual(source.tanks, restored.tanks), prefix + ".tanks");
        h.assertTrue(Arrays.deepEquals(source.directions, restored.directions), prefix + ".directions");
        h.assertValueEqual(restored.uuidSet, source.uuidSet, prefix + ".uuidSet");
        h.assertValueEqual(restored.map, source.map, prefix + ".map");
        h.assertValueEqual(restored.aabb, source.aabb, prefix + ".aabb");
        h.assertValueEqual(restored.a.a, source.a.a, prefix + ".a.a (@Codec)");
        h.assertValueEqual(restored.b.a, source.b.a, prefix + ".b.a (nested holder)");
        h.assertValueEqual(restored.b.b, source.b.b, prefix + ".b.b (nested holder)");
        // b.c is @SyncToClient only, so the nested sync state must not come back from the disk
        // payload: the freshly cleared receiver keeps its own (null) value.
        h.assertTrue(restored.b.c.value == null, prefix + ".b.c.value (sync-only nested state persisted)");
        h.assertTrue(Arrays.equals(source.ints, restored.ints), prefix + ".ints");
        h.assertTrue(Arrays.equals(source.floats, restored.floats), prefix + ".floats");
        h.assertValueEqual(restored.intList, source.intList, prefix + ".intList");
        // instanceAsValue: the nullable container instance (and its contents) comes back as a whole.
        h.assertValueEqual(restored.optionalInts, source.optionalInts, prefix + ".optionalInts (instanceAsValue)");
        h.assertValueEqual(restored.object2IntMap, source.object2IntMap, prefix + ".object2IntMap");
        h.assertValueEqual(restored.handler.serializeNBT(h.getLevel().registryAccess()),
                source.handler.serializeNBT(h.getLevel().registryAccess()), prefix + ".handler");
        h.assertValueEqual(handlerTags(h, restored), handlerTags(h, source), prefix + ".handlerArray");
        h.assertValueEqual(restored.module.ticks, source.module.ticks, prefix + ".module.ticks");
        h.assertValueEqual(restored.module.name, source.module.name, prefix + ".module.name");
        h.assertValueEqual(restored.module.values, source.module.values, prefix + ".module.values");
        h.assertValueEqual(restored.globalPos, source.globalPos, prefix + ".globalPos");
        h.assertValueEqual(restored.sectionPos.asLong(), source.sectionPos.asLong(), prefix + ".sectionPos");
        h.assertValueEqual(restored.vec3i, source.vec3i, prefix + ".vec3i");
        h.assertValueEqual(restored.loaded, source.loaded, prefix + ".loaded (save listener)");
        // manual is @SyncToClient only (autoDetect = false), so it must not be in the disk payload;
        // the cleared receiver would otherwise show the mutated value.
        h.assertValueEqual(restored.manual, 0, prefix + ".manual (sync-only value persisted to disk)");
        h.assertTrue(stacksEqual(source.savedEmpty, restored.savedEmpty), prefix + ".savedEmpty (saveEmpty)");
        h.assertTrue(nbtEquals(source.tagData, restored.tagData), prefix + ".tagData (@Conversion)");
        h.assertValueEqual(restored.converted, source.converted, prefix + ".converted (toField)");
    }

    /** Asserts every {@code @SyncToClient} family of a network round trip. */
    private static void expectSynced(GameTestHelper h, String prefix, TestBlockEntity source, TestBlockEntity target) {
        h.assertValueEqual(target.a.a, source.a.a, prefix + ".a.a (@Codec)");
        h.assertValueEqual(target.b.c.value, source.b.c.value, prefix + ".b.c.value (nested holder sync)");
        h.assertTrue(ItemStack.matches(source.stack, target.stack), prefix + ".stack components");
        h.assertTrue(fluidsEqual(source.fluid, target.fluid), prefix + ".fluid");
        h.assertTrue(stacksEqual(source.stacks, target.stacks), prefix + ".stacks");
        h.assertTrue(fluidsEqual(source.tanks, target.tanks), prefix + ".tanks");
        h.assertValueEqual(target.heldItem.value(), source.heldItem.value(), prefix + ".heldItem (Holder<Item>)");
        h.assertTrue(Arrays.equals(source.ints, target.ints), prefix + ".ints");
        h.assertTrue(Arrays.equals(source.floats, target.floats), prefix + ".floats");
        h.assertValueEqual(target.intList, source.intList, prefix + ".intList");
        h.assertValueEqual(target.object2IntMap, source.object2IntMap, prefix + ".object2IntMap");
        h.assertValueEqual(target.handler.serializeNBT(h.getLevel().registryAccess()),
                source.handler.serializeNBT(h.getLevel().registryAccess()), prefix + ".handler");
        h.assertValueEqual(handlerTags(h, target), handlerTags(h, source), prefix + ".handlerArray");
        h.assertValueEqual(target.module.ticks, source.module.ticks, prefix + ".module.ticks");
        h.assertValueEqual(target.module.name, source.module.name, prefix + ".module.name");
        h.assertValueEqual(target.globalPos, source.globalPos, prefix + ".globalPos");
        h.assertValueEqual(target.synced, source.synced, prefix + ".synced");
        h.assertValueEqual(target.manual, source.manual, prefix + ".manual");
        h.assertValueEqual(target.optionalInts, source.optionalInts, prefix + ".optionalInts (instanceAsValue)");
        h.assertTrue(nbtEquals(source.tagData, target.tagData), prefix + ".tagData (@Conversion)");
        h.assertValueEqual(target.converted, source.converted, prefix + ".converted (toField)");
    }

    /** Compares an {@code ItemStackHandler[]} through the tags the framework itself uses. */
    private static List<CompoundTag> handlerTags(GameTestHelper h, TestBlockEntity be) {
        return Arrays.stream(be.handlerArray)
                .map(handler -> handler.serializeNBT(h.getLevel().registryAccess()))
                .toList();
    }

    /**
     * {@code ItemStack} has no content {@code equals}, so an array of them is compared slot by slot
     * through {@code ItemStack.matches} (item, count and — since 1.21 — the data components).
     */
    private static boolean stacksEqual(ItemStack[] a, ItemStack[] b) {
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
     * Compares fluid kind, amount and components through {@link FluidStack#matches} — a {@code null}
     * slot is only equal to another {@code null} slot, so a missing tank keeps meaning "no fluid".
     */
    private static boolean fluidsEqual(FluidStack a, FluidStack b) {
        if (a == null || b == null) return a == b;
        return FluidStack.matches(a, b);
    }

    private static boolean fluidsEqual(FluidStack[] a, FluidStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!fluidsEqual(a[i], b[i])) return false;
        }
        return true;
    }

    /**
     * {@code CompoundTag.equals} is not reliable enough to compare two restored tags (it is
     * component-wise deep but the entries are read back into different {@code Tag} instances), so the
     * comparison walks the key set and compares the entry count explicitly.
     */
    private static boolean nbtEquals(CompoundTag a, CompoundTag b) {
        if (a == null || b == null) return a == b;
        if (!a.getAllKeys().equals(b.getAllKeys())) return false;
        for (String key : a.getAllKeys()) {
            Tag x = a.get(key);
            Tag y = b.get(key);
            if (!Objects.equals(x, y)) return false;
        }
        return true;
    }

    /** {@code true} when the action throws the {@code IllegalArgumentException} the API documents. */
    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        } catch (RuntimeException other) {
            return false;
        }
    }

    /**
     * A tiny value type registered into {@link DataSyncCodec} by the {@link Registry} test.
     *
     * <p>It is deliberately unique to this suite: registering a codec for a class mutates the global
     * {@code DataSyncCodec} table for the rest of the JVM, so reusing a type another suite registers
     * (or a plain JDK type) would leak state across tests.</p>
     */
    record GameTestTag(String name, int value) {
    }
}
