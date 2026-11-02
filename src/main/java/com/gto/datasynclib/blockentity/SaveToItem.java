package com.gto.datasynclib.blockentity;

import com.gto.datasynclib.annotations.AddToManager;
import com.gto.datasynclib.annotations.SaveToDisk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field whose value travels with the <strong>item</strong> a block entity drops.
 *
 * <p>Where {@link SaveToDisk} stores a field into the world save, this annotation stores it into
 * the block's item: the marked fields are written when the block is broken and read back when the
 * placed block entity is built from that item. The value therefore follows the item through a
 * break → pick up → place cycle, which is how a machine keeps its configuration when it is moved.</p>
 *
 * <h3>What is written</h3>
 * <p>Exactly the marked fields — no more. A field that is only {@code @SaveToDisk} stays out of the
 * item, and a marked field does not need {@code @SaveToDisk} at all: like
 * {@link AddToManager}, {@code @SaveToItem} puts its field under management on its own (and into
 * the {@code writeAllToValue}/{@code readAllFromValue} bulk paths). A field may carry both
 * annotations; it is then written to both the world save and the item.</p>
 *
 * <p>The payload follows the usual field rules: the field's codec, plus the filters the write path
 * already applies — a field that also carries {@code @SaveToDisk} keeps that annotation's
 * {@code skipWhen} predicate and default-value filter, which is why a skip condition is written there
 * rather than here. Fields are written
 * through {@link com.gto.datasynclib.FieldDataManager#writeFieldsToValue} and read back with
 * {@link com.gto.datasynclib.FieldDataManager#readFieldsFromValue}, i.e. the same carrier format a
 * save file holds.</p>
 *
 * <h3>Where the payload lives</h3>
 * <p>In the item's <strong>root tag</strong>, under {@code "fields"} (a byte array) and
 * {@code "fields_dataVersion"} (the
 * {@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity#VERSION} it was written with).
 * The item stays a plain block item — nothing goes under {@code "BlockEntityTag"} — and the block
 * reads the tag back when it is placed.</p>
 *
 * <h3>How the item side is driven</h3>
 * <p>The block entity implements {@link IDropSave} (our
 * {@code FieldDataHolderBlockEntity} does), and the block calls it — the GregTech Modern
 * {@code IDropSaveMachine} shape:</p>
 * <ul>
 *   <li>{@code getDrops} → {@code saveToItem(tag, false)} on the block's own drop,</li>
 *   <li>{@code setPlacedBy} → {@code loadFromItem(tag)} on the new block entity,</li>
 *   <li>{@code getCloneItemStack} → {@code saveToItem(tag, true)} for creative pick-block.</li>
 * </ul>
 * <p>Extending {@link com.gto.datasynclib.block.FieldDataHolderBlock} gives all three hooks; a
 * block with its own base class calls the {@link IDropSave#getDrops}, {@link IDropSave#setPlacedBy}
 * and {@link IDropSave#getCloneItemStack} static helpers from its own hooks, and the block entity
 * side stays the same. The library registers this annotation itself (through
 * {@link com.gto.datasynclib.FieldDefinitionStorage#registerAnnotation(Class,
 * FieldDefinitionStorage.AnnotationMetadataFactory)}, with {@link SaveToItemMetadata#create} as the
 * factory) during mod construction, so every holder class is scanned for it.</p>
 *
 * <p><strong>Copying a machine is client-side:</strong> creative pick-block is built on the client
 * from the client's copy of the block entity, and a field marked only with {@code @SaveToItem} is
 * not synced to that copy. Such a clone therefore carries the client's values (in practice the
 * defaults) unless the field is also {@code @SyncToClient}; breaking the block is server-side and
 * always carries the real values.</p>
 *
 * <h3>Conditions and clone support</h3>
 * <p>{@link #saveOnClone()} decides whether the field is also written into a creative pick-block
 * clone (the {@code clone} flag of
 * {@link IDropSave#saveToItem(net.minecraft.nbt.CompoundTag, boolean)}) or only into a real drop. It
 * is parsed once at scan time by the annotation's metadata factory into a
 * {@link SaveToItemMetadata}, stored as
 * {@link com.gto.datasynclib.FieldDefinitionStorage.AnnotatedField#metadata()}.</p>
 *
 * <p>A skip condition is the one of {@code @SaveToDisk}: the payload is written through
 * {@link com.gto.datasynclib.FieldDataManager#writeFieldsToValue}, so the field's
 * {@link SaveToDisk#skipWhen()} predicate, its default value and its {@code saveEmpty} rule apply to
 * the item exactly as they do to a save file. Put {@code @SaveToDisk(enabled = false, skipWhen = "...")}
 * on the field when the predicate should only gate the item:</p>
 *
 * <pre>{@code
 * @SaveToItem(saveOnClone = false)
 * @SaveToDisk(enabled = false, skipWhen = "isUpgradeInactive")
 * private int upgradeTier;
 * }</pre>
 *
 * <p>(There is no {@code skipWhen} on this annotation itself: it would duplicate the rule above while
 * the field can only have one predicate.)</p>
 *
 * @see SaveToDisk
 * @see SaveToItemMetadata
 * @see IDropSave
 * @see com.gto.datasynclib.block.FieldDataHolderBlock
 * @see com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SaveToItem {

    /**
     * Whether this field is written when the item is a <strong>clone</strong> — creative
     * pick-block's copy of the block — rather than a real drop.
     *
     * <p>{@code true} (the default) means the field travels in both cases. Set it to {@code false}
     * for a value that only makes sense on a real drop: the clone then starts without it, while
     * breaking the block still carries it. The flag comes from
     * {@link com.gto.datasynclib.blockentity.IDropSave#saveToItem(net.minecraft.nbt.CompoundTag, boolean)},
     * which receives {@code true} only for {@code getCloneItemStack}.</p>
     *
     * <p>(The attribute cannot be called {@code clone}: an annotation element may not share a name
     * with a method {@link Object} declares.)</p>
     *
     * @return {@code true} if the field is also written into a pick-block clone
     */
    boolean saveOnClone() default true;
}
