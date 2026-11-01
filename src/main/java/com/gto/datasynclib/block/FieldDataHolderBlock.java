package com.gto.datasynclib.block;

import com.gto.datasynclib.blockentity.IDropSave;
import com.gto.datasynclib.blockentity.SaveToItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link Block} base class that carries an {@link IDropSave}'s state into the dropped item
 * and back out of it on placement — the GregTech Modern {@code IDropSaveMachine} pattern.
 *
 * <p>Three hooks, and that is all the block has to do:</p>
 * <ul>
 *   <li>{@link #getDrops} — the loot table still produces the drops; before they are popped, the
 *       block entity's {@link IDropSave#saveToItem(CompoundTag, boolean)} writes its state
 *       into the root tag of the block's own item. No loot table is touched, and only that stack's
 *       NBT changes.</li>
 *   <li>{@link #setPlacedBy} — after the item is placed, the new block entity reads that tag back
 *       with {@link IDropSave#loadFromItem(CompoundTag)}.</li>
 *   <li>{@link #getCloneItemStack} — creative pick-block saves with {@code clone = true}, so the
 *       copied machine keeps its state too.</li>
 * </ul>
 *
 * <p>Only a block entity implementing {@link IDropSave} is involved:
 * {@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity} does, for its
 * {@link SaveToItem @SaveToItem} fields, but a custom block entity
 * can implement the interface itself.</p>
 *
 * <pre>{@code
 * public class MyMachineBlock extends FieldDataHolderBlock {
 *     public MyMachineBlock(Properties properties) {
 *         super(properties);
 *     }
 *
 *     @Override
 *     public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
 *         return new MyMachineBlockEntity(pos, state);   // extends FieldDataHolderBlockEntity
 *     }
 * }
 *
 * public class MyMachineBlockEntity extends FieldDataHolderBlockEntity {
 *     @SaveToItem private int mode;    // travels with the item
 *     @SaveToDisk private int energy;  // world save only
 * }
 * }</pre>
 *
 * <h3>Without this class</h3>
 * <p>A block that already extends another base class keeps it and calls the {@link IDropSave}
 * static helpers from its own hooks — {@link IDropSave#getDrops}, {@link IDropSave#setPlacedBy} and
 * {@link IDropSave#getCloneItemStack}; the block entity side is unchanged either way.</p>
 *
 * @see IDropSave
 * @see SaveToItem
 */
public abstract class FieldDataHolderBlock extends Block implements EntityBlock {

    protected FieldDataHolderBlock(Properties properties) {
        super(properties);
    }

    /**
     * Attaches the block entity's item data to the drop that is this block's item.
     *
     * <p>The block entity comes from the loot context ({@link LootContextParams#BLOCK_ENTITY}), so
     * a player break, an explosion, silk touch and any other loot-table drop all take this path:
     * whatever the table produced is passed through untouched, and only the stack that is this
     * block's own item gets the tag. The tag is removed again when the block entity wrote nothing,
     * so an ordinary drop stays free of empty NBT. Only the first matching stack is written to, so
     * a table that somehow drops the block twice cannot duplicate the state.</p>
     *
     * @param state  the broken block state
     * @param params the loot context of the break
     * @return the drops, with the item data attached to the block's own item
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return IDropSave.getDrops(this, super.getDrops(state, params), params);
    }

    /**
     * Hands the placed stack's tag to the new block entity.
     *
     * <p>{@link Block#setPlacedBy} runs on both sides; the load is server-side only, which is where
     * the block entity's state matters. A stack without a tag, or a block entity that is not an
     * {@link IDropSave}, does nothing.</p>
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        IDropSave.setPlacedBy(level, pos, stack);
    }

    /**
     * Copies the block entity's state into the item creative pick-block produces, flagged as a
     * clone so a machine can tell it apart from a real drop.
     */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return IDropSave.getCloneItemStack(super.getCloneItemStack(level, pos, state), level, pos);
    }
}
