package com.gto.datasynclib;

import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Core interface for per-field synchronization and persistence operations.
 *
 * <p>Each managed field (primitive, object, collection, map, or array) is represented
 * by a {@code DataField} implementation created by the factory registered for that
 * field's type in {@link com.gto.datasynclib.FieldDefinitionStorage}.</p>
 *
 * <h3>Two implementation families:</h3>
 * <ul>
 *   <li>{@link com.gto.datasynclib.field.AbstractField AbstractField} — for value-type
 *       fields (primitives and objects). Tracks a snapshot of the previous value for
 *       change detection.</li>
 *   <li>{@link com.gto.datasynclib.field.access.AbstractFieldAccess AbstractFieldAccess} —
 *       for container-type fields (collections, maps, arrays). Works with the mutable
 *       container in-place, tracking instance identity and content changes.</li>
 * </ul>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@link #detectChange} — called during dirty flag update to check if the field
 *       value has changed since last sync</li>
 *   <li>{@link #writeToBuffer} — serialize the current value to the network buffer</li>
 *   <li>{@link #readFromBuffer} — deserialize from the network buffer and apply the value</li>
 *   <li>{@link #writeToData} — serialize to a Data object for disk persistence</li>
 *   <li>{@link #readFromData} — deserialize from a Data object during loading</li>
 * </ol>
 *
 * @param <T> the declared type of the field this DataField manages
 * @see DataFieldDefinition
 * @see com.gto.datasynclib.FieldDataManager
 */
public interface DataField<T> {

    /**
     * @return the metadata definition for the field this DataField manages
     */
    DataFieldDefinition<T> getDefinition();

    /**
     * Marks this field as changed for the given source object, forcing it to be
     * included in the next synchronization.
     *
     * @param source the owning object (from {@link com.gto.datasynclib.IFieldDataHolder#getSource})
     */
    void markAsChanged(@NotNull Object source);

    /**
     * Checks whether this field is currently marked as changed for the given source.
     *
     * @param source the owning object
     * @return {@code true} if the field has been marked as changed
     */
    boolean isChanged(@NotNull Object source);

    /**
     * Clears the changed flag after a successful synchronization.
     *
     * @param source the owning object
     */
    void clearChanged(@NotNull Object source);

    /**
     * Detects whether the field's value has changed by comparing the current value
     * against a previously-stored snapshot.
     *
     * @param side   the logical side initiating the detection
     * @param source the owning object
     * @param autoOnly {@code true} to only detect changes on fields whose
     *                 {@code autoUpdate} annotation is enabled; {@code false} to
     *                 force detection on all fields regardless of annotation
     * @return {@code true} if the value has changed and the field should be synced
     */
    boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean autoOnly);

    /**
     * Writes the field's current value to a network buffer.
     * The implementation should also update its internal snapshot for future
     * change detection.
     *
     * @param side    the logical side performing the write
     * @param source  the owning object
     * @param data    the network buffer to write to
     * @param writeAll {@code true} to write regardless of dirty state (full sync);
     *                 {@code false} for incremental sync
     */
    void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll);

    /**
     * Reads the field's value from a network buffer and applies it to the source object.
     * Should fire the registered listener if one is configured.
     *
     * @param side   the logical side performing the read
     * @param source the owning object
     * @param data   the network buffer to read from
     */
    void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data);

    /**
     * Serializes the field's current value to a {@link com.gto.datasynclib.datastream.data.Data}
     * tree for disk persistence.
     *
     * @param source the owning object
     * @return the serialized data, or {@link com.gto.datasynclib.datastream.data.NullData#NONE}
     *         to suppress this field from being written
     */
    @NotNull
    Data writeToData(@NotNull Object source);

    /**
     * Deserializes the field's value from a {@link com.gto.datasynclib.datastream.data.Data}
     * tree and applies it to the source object.
     *
     * @param source      the owning object
     * @param data        the serialized data to read from
     * @param dataVersion the data format version, for migration support
     */
    void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion);

    /**
     * Indicates whether this field type requires mandatory change detection.
     * When {@code true}, {@link #detectChange} is always called during dirty flag
     * updates, even if the field hasn't been marked as changed.
     *
     * <p>This is used by container types (e.g., {@code IFieldDataHolder} accessors)
     * that have their own internal change tracking and need detection to run on
     * every sync cycle.</p>
     *
     * @return {@code true} if change detection is always required, {@code false} (default) otherwise
     */
    default boolean mustDetect() {
        return false;
    }

    interface Factory<T> {

        @SuppressWarnings("rawtypes")
        DataField<T> create(DataFieldDefinition definition);
    }

    record CustomFactory<T>(Predicate<Class<?>> predicate, Function<Class<?>, Factory<T>> factory, int priority) {

        static final Comparator<CustomFactory<?>> COMPARATOR = Comparator.comparingInt(f -> -f.priority);
    }

    record CustomGenericFactory<T>(BiPredicate<Class<?>, Class<?>[]> predicate,
                                   BiFunction<Class<?>, Class<?>[], Factory<T>> factory, int priority) {

        static final Comparator<CustomGenericFactory<?>> COMPARATOR = Comparator.comparingInt(f -> -f.priority);
    }
}
