package com.gto.datasynclib.field.access;

import com.gto.datasynclib.ChildFieldDataHolder;
import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Field access used by the child-manager mode of
 * {@link com.gto.datasynclib.annotations.AdditionalHolder @AdditionalHolder}
 * ({@code childManager = true}) for a field of arbitrary (non-{@link IFieldDataHolder}) type {@code T}.
 *
 * <p>The annotated field's object is <em>not</em> flattened into the parent manager.
 * Instead a dedicated {@link com.gto.datasynclib.FieldDataManager} is generated for it
 * (via {@link ChildFieldDataHolder}), and the whole object is serialized as a single
 * field — functionally equivalent to {@link FieldDataHolderAccess} but able to wrap any
 * plain sub-object into an {@link IFieldDataHolder} adapter.</p>
 *
 * <p>Because the field's type is the raw sub-object type {@code T} (not necessarily
 * {@code IFieldDataHolder}), canonical {@code AbstractFieldAccess} machinery operates on
 * {@code T}, and each serialize step first wraps the current {@code T} value through
 * {@link #asHolder} into an {@link IFieldDataHolder} before delegating to its manager.
 * The adapter is cached by object identity so identity-stable fields reuse one manager,
 * while swapped-in sub-objects (non-final fields) get a fresh one.</p>
 *
 * @param <T> the raw sub-object type of the annotated field
 */
public final class ChildManagerAccess<T> extends AbstractFieldAccess<T> {

    /**
     * Current sub-object being managed and its cached child-manager holder (single slot).
     */
    private Object cachedTarget;
    private IFieldDataHolder cachedHolder;

    public ChildManagerAccess(DataFieldDefinition<T> definition) {
        super(definition);
    }

    /**
     * Resolves the {@link IFieldDataHolder} managing the given raw sub-object, wrapping it
     * in a {@link ChildFieldDataHolder} unless it already is a holder. The adapter is cached
     * against the current sub-object's identity so identity-stable (e.g. {@code final}) fields
     * reuse one {@link com.gto.datasynclib.FieldDataManager}; when a swapped-in sub-object
     * (non-final field) is observed, the previous adapter is dropped to avoid a leak.
     */
    @Nullable
    private IFieldDataHolder asHolder(@Nullable T raw) {
        if (raw == null) {
            cachedTarget = null;
            cachedHolder = null;
            return null;
        }
        if (raw instanceof IFieldDataHolder holder) {
            cachedTarget = raw;
            cachedHolder = holder;
            return holder;
        }
        if (cachedTarget == raw && cachedHolder != null) return cachedHolder;
        cache(raw, new ChildFieldDataHolder(raw));
        return cachedHolder;
    }

    private void cache(Object target, IFieldDataHolder holder) {
        this.cachedTarget = target;
        this.cachedHolder = holder;
    }

    @Override
    public boolean mustDetect() {
        return true;
    }

    @Override
    public void markAsChanged(@NotNull Object source) {
        changed = true;
        var holder = asHolder(getInstance(source));
        if (holder == null) return;
        holder.getFieldDataManager().markAsChanged();
    }

    @Override
    public void clearChanged(@NotNull Object source) {
        changed = false;
        var holder = asHolder(getInstance(source));
        if (holder == null) return;
        holder.getFieldDataManager().clearChanged();
    }

    @Override
    public boolean isChanged(@NotNull Object source) {
        var holder = asHolder(getInstance(source));
        if (holder == null) return changed;
        return changed || holder.getFieldDataManager().isChanged();
    }

    @Override
    protected boolean hasChange(@NotNull LogicalSide side, @NotNull T instance, boolean autoOnly) {
        var holder = asHolder(instance);
        if (holder == null) return false;
        return holder.getFieldDataManager().updateFieldDirtyFlags(side, autoOnly);
    }

    @Override
    protected void doWriteBuffer(@NotNull LogicalSide side, @NotNull T instance, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var holder = asHolder(instance);
        if (holder == null) return;
        data.writeByteArray(holder.getFieldDataManager().writeToNetworkBuffer(side, writeAll));
    }

    @Override
    protected void doReadBuffer(@NotNull LogicalSide side, @NotNull T instance, @NotNull FriendlyByteBuf data) {
        var holder = asHolder(instance);
        if (holder == null) return;
        holder.getFieldDataManager().readFromNetworkBuffer(side, data.readByteArray());
    }

    @Override
    protected @NotNull Data doWriteData(@NotNull Object source, @NotNull T instance) {
        var holder = asHolder(instance);
        if (holder == null) return NullData.INSTANCE;
        return holder.getFieldDataManager().writeToData();
    }

    @Override
    protected void doReadData(@NotNull T instance, @NotNull Data data, int dataVersion) {
        var holder = asHolder(instance);
        if (holder == null) return;
        holder.getFieldDataManager().readFromData(data, dataVersion);
    }
}
