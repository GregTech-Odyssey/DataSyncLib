package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.StringMapData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight {@link IFieldDataHolder} adapter that grants a plain sub-object its
 * own dedicated {@link FieldDataManager}, without requiring the sub-object to implement
 * {@link IFieldDataHolder} itself.
 *
 * <p>Used by the child-manager mode of
 * {@link com.gto.datasynclib.annotations.AdditionalHolder @AdditionalHolder}
 * ({@code childManager = true}). The annotated field is <em>not</em> flattened into
 * the parent manager; instead it is wrapped in this adapter, and the whole object is
 * managed (sync + persistence) as one atomic field through
 * {@link com.gto.datasynclib.field.access.ChildManagerAccess}.</p>
 *
 * <p>In contrast to the flattened {@code @AdditionalHolder} path (which composes a
 * getter chain and hosts nested fields on the parent manager), this adapter hosts the
 * sub-object's annotated fields on a <em>separate</em> {@code FieldDataManager} that
 * scans the sub-object's own class hierarchy.</p>
 *
 * <p>The {@link #getSource(DataFieldDefinition)} override returns the wrapped sub-object:
 * because the child manager is built with that sub-object's class as the holder class,
 * its top-level field definitions carry {@code source == null}, and the default
 * {@link IFieldDataHolder#getSource} implementation would wrongly return {@code this}
 * (the adapter). Delegating to the target fixes ownership resolution and custom-data
 * callbacks.</p>
 */
public final class ChildFieldDataHolder implements IFieldDataHolder {

    private final Object target;
    private final Class<?> targetClass;
    private final LazyFieldDataManager fieldDataManager;

    public ChildFieldDataHolder(@NotNull Object target) {
        this.target = target;
        this.targetClass = target.getClass();
        this.fieldDataManager = new LazyFieldDataManager(this, targetClass);
    }

    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager.get();
    }

    /**
     * @return the wrapped sub-object that owns the managed fields
     */
    @SuppressWarnings("unchecked")
    public <T> T target() {
        return (T) target;
    }

    @Override
    public Object getSource(DataFieldDefinition<?> definition) {
        var source = definition.source;
        // Child manager scans the sub-object's own class, so its fields carry no parent chain.
        // Resolve to the wrapped target directly.
        if (source == null) return target;
        return source.apply(target);
    }

    @Override
    public void writeCustomSaveData(StringMapData data) {
        if (target instanceof IFieldDataHolder h) h.writeCustomSaveData(data);
    }

    @Override
    public void readCustomSaveData(StringMapData data, int dataVersion) {
        if (target instanceof IFieldDataHolder h) h.readCustomSaveData(data, dataVersion);
    }

    @Override
    public void writeCustomSyncData(FriendlyByteBuf buf, boolean writeAll) {
        if (target instanceof IFieldDataHolder h) h.writeCustomSyncData(buf, writeAll);
    }

    @Override
    public void readCustomSyncData(FriendlyByteBuf buf) {
        if (target instanceof IFieldDataHolder h) h.readCustomSyncData(buf);
    }

    @Override
    public void scheduleUpdate(LogicalSide side) {
        if (target instanceof IFieldDataHolder h) h.scheduleUpdate(side);
    }

    @Nullable
    @Override
    public String toString() {
        return "ChildFieldDataHolder[" + targetClass.getName() + "]";
    }
}
