package com.gto.datasynclib.annotations;

import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.datastream.codec.ValueOps;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation that adds a field to the {@link FieldDataManager} for management
 * without enabling synchronization or persistence on its own.
 *
 * <p>A field annotated solely with {@code @AddToManager} (without {@link SaveToDisk},
 * {@link SyncToClient}, or {@link SyncToServer}) is registered in the holder's definition
 * list ({@code FieldDefinitionStorage#allDefinitions}) and participates in
 * {@link FieldDataManager#writeAllToValue(ValueOps)} / {@link FieldDataManager#readAllFromValue(Object, ValueOps)},
 * but is NOT included in automatic sync or incremental save operations.</p>
 *
 * <p>This is conceptually the "base annotation" that the other three annotations
 * provide additional behavior on top of. Use this when you want a field to be
 * managed (e.g., for bulk export/import) without triggering incremental sync or
 * disk persistence.</p>
 *
 * @see SaveToDisk
 * @see SyncToClient
 * @see SyncToServer
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AddToManager {

}
