package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.FieldDefinitionStorage;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.List;

/**
 * Block-entity side of "the machine's state travels with its item", modelled on GregTech Modern's
 * {@code IDropSaveMachine}.
 *
 * <p>The block drives it — the block entity never watches its own break or placement:</p>
 * <ul>
 *   <li>{@link com.gto.datasynclib.block.FieldDataHolderBlock#getDrops} calls
 *       {@link #modifyDrops(List)} and then {@link #saveToItem(CompoundTag, boolean)} with the
 *       root tag of the dropped block item;</li>
 *   <li>{@link com.gto.datasynclib.block.FieldDataHolderBlock#setPlacedBy} calls
 *       {@link #loadFromItem(CompoundTag)} with the root tag of the placed stack;</li>
 *   <li>{@link com.gto.datasynclib.block.FieldDataHolderBlock#getCloneItemStack} calls
 *       {@link #saveToItem(CompoundTag, boolean)} with {@code clone = true}, so creative
 *       pick-block also copies the state.</li>
 * </ul>
 *
 * <p>{@link FieldDataHolderBlockEntity} implements this for the
 * {@link SaveToItem @SaveToItem} fields; a custom block entity can
 * implement the interface itself (the block base only needs the interface, not our base class).</p>
 *
 * <p>The static helpers below are the block-side plumbing: {@link
 * com.gto.datasynclib.block.FieldDataHolderBlock} calls them, and a block that already extends
 * another base class calls them from its own {@code getDrops}/{@code setPlacedBy}/{@code
 * getCloneItemStack} instead of extending it.</p>
 *
 * <p>The tag is the item's own root tag, which keeps the item a normal block item: nothing is
 * stored under {@code BlockEntityTag}, so vanilla's block-item placement does not touch it.</p>
 *
 * @see com.gto.datasynclib.block.FieldDataHolderBlock
 * @see SaveToItem
 */
public interface IDropSave {

    String ITEM_DATA_KEY = "fields";

   String ITEM_DATA_VERSION_KEY = "fields_dataVersion";
    /**
     * Block side of {@code getDrops}: lets the block entity adjust the drops, then writes its state
     * into the root tag of the drop that is this block's own item.
     *
     * <p>Only the first matching stack is written to, so a table that somehow drops the block twice
     * cannot duplicate the state; a tag that ends up empty is removed again.</p>
     *
     * @param block  the broken block, used for {@link Block#asItem()}
     * @param drops  the drops the loot table produced, already collected
     * @param params the loot context of the break, which carries the block entity
     * @return {@code drops}, for chaining from an override
     */
    static List<ItemStack> getDrops(Block block, List<ItemStack> drops, LootParams.Builder params) {
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof IDropSave save) {
            save.modifyDrops(drops);
            var item = block.asItem();
            for (var drop : drops) {
                if (drop.getItem() != item) continue;
                var tag = drop.getOrCreateTag();
                save.saveToItem(tag, false);
                if (tag.isEmpty()) drop.setTag(null);
                break;
            }
        }
        return drops;
    }

    /**
     * Block side of {@code setPlacedBy}: on the server, hands the placed stack's root tag to the new
     * block entity.
     *
     * @param level the level the block was placed in
     * @param pos   the placed block's position
     * @param stack the stack that was placed
     */
    static void setPlacedBy(Level level, BlockPos pos, ItemStack stack) {
        if (level.isClientSide) return;
        var tag = stack.getTag();
        if (tag == null || tag.isEmpty()) return;
        if (level.getBlockEntity(pos) instanceof IDropSave save) save.loadFromItem(tag);
    }

    /**
     * Block side of {@code getCloneItemStack}: writes the state into the pick-block stack with
     * {@code clone = true}, and removes the tag again when nothing was written.
     *
     * @param stack the clone stack to fill, as {@code super.getCloneItemStack} produced it
     * @param level the level the block sits in
     * @param pos   the block's position
     * @return {@code stack}, for chaining from an override
     */
    static ItemStack getCloneItemStack(ItemStack stack, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof IDropSave machine) {
            var tag = stack.getOrCreateTag();
            machine.saveToItem(tag, true);
            if (tag.isEmpty()) stack.setTag(null);
        }
        return stack;
    }

    /**
     * Writes the holder's {@code @SaveToItem} fields into {@code tag}, the root tag of its item.
     *
     * <p>The payload is what {@link FieldDataManager#writeFieldsToValue} produces for the marked
     * fields — the same carrier format a save file holds, so the field's {@code @SaveToDisk}
     * {@code skipWhen} predicate, default value and {@code saveEmpty} rule apply here exactly as they
     * do to a save — stored under {@link #ITEM_DATA_KEY} with {@code version} under
     * {@link #ITEM_DATA_VERSION_KEY}.</p>
     *
     * <p>{@code @SaveToItem(saveOnClone = false)} leaves a field out when {@code clone} is
     * {@code true}; the flag comes from the parsed {@link SaveToItemMetadata} the annotation's
     * metadata factory stored during the scan.</p>
     *
     * <p>Nothing is written when no field survives the filters or when every surviving field had
     * nothing to store, so the caller can clean an empty tag off the stack afterwards.</p>
     *
     * @param holder  the block entity whose fields travel with the item
     * @param tag     the item's root tag, already writable
     * @param clone   {@code true} for a creative pick-block clone, {@code false} for a real drop
     * @param version the data version the payload is written with
     */
    static void saveToItem(IFieldDataHolder holder, CompoundTag tag, boolean clone, int version) {
        var manager = holder.getFieldDataManager();
        // Both arrays come from a per-class cache: the plain one from the storage (built once with
        // the class's scan), the clone subset from CLONE_FIELDS, so a write allocates nothing.
        var fields = clone ? cloneFields(holder.getClass()) : allFields(holder.getClass());
        if (fields.length == 0) return;
        var ops = JavaValueOps.create(version);
        var data = manager.writeFieldsToValue(ops, fields);
        if (ops.isNull(data)) return;
        tag.putByteArray(ITEM_DATA_KEY, ops.toBytes(data));
        tag.putInt(ITEM_DATA_VERSION_KEY, version);
    }

    /**
     * Reads the {@link #saveToItem(IFieldDataHolder, CompoundTag, boolean, int) saved} fields back
     * from {@code tag}; a field whose key the payload does not contain is left alone, so a value that
     * was filtered out on the way out keeps the value the fresh block entity starts with.
     *
     * @param holder the block entity being restored
     * @param tag    the item's root tag; an absent payload is a no-op
     */
    static void loadFromItem(IFieldDataHolder holder, CompoundTag tag) {
        if (!(tag.get(ITEM_DATA_KEY) instanceof ByteArrayTag bytes)) return;
        var fields = allFields(holder.getClass());
        if (fields.length == 0) return;
        holder.getFieldDataManager().readFieldsFromValue(
                JavaValueOps.INSTANCE.fromBytes(bytes.getAsByteArray()),
                JavaValueOps.create(tag.getInt(ITEM_DATA_VERSION_KEY)), fields);
    }

    /**
     * @param holderClass the block entity class
     * @return its {@code @SaveToItem} definitions, cached on the storage
     */
    private static DataFieldDefinition<?>[] allFields(Class<?> holderClass) {
        return FieldDefinitionStorage.get(holderClass).getAnnotatedFieldDefinitions(SaveToItem.class);
    }

    /**
     * @param holderClass the block entity class
     * @return the subset of {@link #allFields(Class)} that is also written into a creative pick-block
     * clone, computed once per class (see {@link SaveToItemMetadata#cloneFields(Class)})
     */
    private static DataFieldDefinition<?>[] cloneFields(Class<?> holderClass) {
        return SaveToItemMetadata.cloneFields(holderClass);
    }


    /**
     * Called with the mutable drop list before the item data is attached, so a block entity can add
     * or remove drops of its own.
     *
     * @param drops the drops the loot table produced, modifiable
     */
    default void modifyDrops(List<ItemStack> drops) {
    }

    /**
     * Writes this block entity's state into {@code tag}, the root tag of its item.
     *
     * @param tag   the item's root tag, already created and writable
     * @param clone {@code true} when the stack is being created by creative pick-block rather than
     *              by breaking the block, so a machine can leave out data that only makes sense on
     *              a real drop
     */
    default void saveToItem(CompoundTag tag, boolean clone) {
    }

    /**
     * Reads the state back from {@code tag}, the root tag of the stack being placed.
     *
     * @param tag the item's root tag; it may hold anything else as well
     */
    default void loadFromItem(CompoundTag tag) {
    }
}
