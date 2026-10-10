package com.gto.datasynclib;

import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.datastream.codec.ByteBufCodecs;
import com.gto.datasynclib.datastream.codec.StreamCodec;
import com.gto.datasynclib.datastream.codec.ValueCodec;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.util.ReflectUtil;
import it.unimi.dsi.fastutil.Hash;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Package-private class that parses and holds the extracted metadata from field annotations.
 *
 * <p>This is NOT the annotations themselves, but a processed/digested form containing the parsed
 * configuration values, resolved Method references, and Strategy/Codec instances. Each instance
 * corresponds to one annotated field in a managed class.
 *
 * <p>Internal implementation detail — external code should never need to construct or interact
 * with this class directly.
 */
@Getter
@Accessors(fluent = true)
final class FieldAnnotationMetadata {

    private final SaveToDisk saveToDisk;
    private final SyncToClient syncToClient;
    private final SyncToServer syncToServer;

    private final String key;
    private final boolean saveEmpty;
    private final Object defaultValue;
    private final Method defaultValueGetter;
    /**
     * Resolved {@code @SaveToDisk(listener = "...")} target, or {@code null} when the
     * annotation does not declare one. Invoked after the field has been read back from disk.
     */
    private final Method readSaveListener;
    private final Method clientUpdateListener;
    private final Method serverUpdateListener;
    private final Method saveSkipWhen;
    private final Method syncToClientSkipWhen;
    private final Method syncToServerSkipWhen;
    private final Hash.Strategy strategy;
    private final StreamCodec streamCodec;
    /**
     * The {@code @Codec(saveCodec = "...")} static field, seen as a value codec — the persistence
     * half of a codec declared on the field. {@code null} when the field declares none.
     */
    private final ValueCodec valueCodec;
    private final Method writeToValue;
    private final Method readFromValue;
    private final Method writeToBuffer;
    private final Method readFromBuffer;
    private final boolean scheduleClientUpdate;
    private final boolean scheduleServerUpdate;
    private final boolean autoSyncToClient;
    private final boolean autoSyncToServer;
    private final boolean hasGeneric;
    private final boolean hasAccessAnnotation;
    private final boolean instanceAsValue;

    FieldAnnotationMetadata(Class<?> clazz, Field field, Class<?> type, SaveToDisk saveToDisk, SyncToClient syncToClient, SyncToServer syncToServer) {
        var access = field.getAnnotation(Access.class);
        var generic = field.getAnnotation(Generic.class);
        this.hasGeneric = generic != null;
        this.hasAccessAnnotation = access != null;
        this.saveToDisk = saveToDisk;
        this.syncToClient = syncToClient;
        this.syncToServer = syncToServer;
        this.key = saveToDisk == null || saveToDisk.key().isEmpty() ? field.getName() : saveToDisk.key();
        this.saveEmpty = saveToDisk == null || saveToDisk.saveEmpty();
        this.defaultValue = saveToDisk == null ? null : ReflectUtil.parse(type, saveToDisk.defaultValue());
        this.scheduleClientUpdate = syncToClient != null && syncToClient.scheduleUpdate();
        this.scheduleServerUpdate = syncToServer != null && syncToServer.scheduleUpdate();
        this.autoSyncToClient = syncToClient != null && syncToClient.autoDetect();
        this.autoSyncToServer = syncToServer != null && syncToServer.autoDetect();
        this.instanceAsValue = this.hasAccessAnnotation && access.instanceAsValue();

        if (saveToDisk != null && !saveToDisk.defaultValueGetter().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, saveToDisk.defaultValueGetter());
            method.setAccessible(true);
            this.defaultValueGetter = method;
        } else {
            this.defaultValueGetter = null;
        }

        // Load listener: resolved with the field's exact declared type (same rule as `skipWhen`),
        // i.e. a non-static method on the declaring class taking the field type as its only
        // parameter. Stored as a Method here and turned into a MethodHandle by
        // DataFieldDefinition; the readFromValue implementations invoke it after a disk load.
        if (saveToDisk != null && !saveToDisk.listener().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, saveToDisk.listener(), type);
            method.setAccessible(true);
            this.readSaveListener = method;
        } else {
            this.readSaveListener = null;
        }

        if (syncToClient != null && !syncToClient.listener().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, syncToClient.listener(), type, type);
            method.setAccessible(true);
            this.clientUpdateListener = method;
        } else {
            this.clientUpdateListener = null;
        }

        if (syncToServer != null && !syncToServer.listener().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, syncToServer.listener(), type, type);
            method.setAccessible(true);
            this.serverUpdateListener = method;
        } else {
            this.serverUpdateListener = null;
        }

        if (saveToDisk != null && !saveToDisk.skipWhen().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, saveToDisk.skipWhen(), type);
            method.setAccessible(true);
            this.saveSkipWhen = method;
        } else {
            this.saveSkipWhen = null;
        }

        if (syncToClient != null && !syncToClient.skipWhen().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, syncToClient.skipWhen(), type);
            method.setAccessible(true);
            this.syncToClientSkipWhen = method;
        } else {
            this.syncToClientSkipWhen = null;
        }

        if (syncToServer != null && !syncToServer.skipWhen().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, syncToServer.skipWhen(), type);
            method.setAccessible(true);
            this.syncToServerSkipWhen = method;
        } else {
            this.syncToServerSkipWhen = null;
        }

        var strategy = field.getAnnotation(Strategy.class);
        if (strategy == null) {
            this.strategy = null;
        } else {
            try {
                var f = clazz.getDeclaredField(strategy.value());
                f.setAccessible(true);
                this.strategy = (Hash.Strategy) f.get(null);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        var codec = field.getAnnotation(Codec.class);
        if (codec == null) {
            this.valueCodec = null;
            this.streamCodec = null;
            this.writeToValue = null;
            this.readFromValue = null;
            this.writeToBuffer = null;
            this.readFromBuffer = null;
        } else {
            if (codec.saveCodec().isEmpty()) {
                if (!codec.writeToValue().isEmpty()) {
                    // the hooks take no source argument: the declaring instance is the handle's
                    // receiver, exactly as the retired Data-era shape worked. The carrier is the
                    // ValueOps interface the annotation documents; JavaValueOps is one carrier
                    var m = ReflectUtil.getAccessibleMethod(clazz, codec.writeToValue(), ValueOps.class, type);
                    m.setAccessible(true);
                    this.writeToValue = m;
                    m = ReflectUtil.getAccessibleMethod(clazz, codec.readFromValue(), ValueOps.class, Object.class);
                    m.setAccessible(true);
                    this.readFromValue = m;
                } else {
                    this.writeToValue = null;
                    this.readFromValue = null;
                }
                if (!codec.writeToBuffer().isEmpty()) {
                    var m = ReflectUtil.getAccessibleMethod(clazz, codec.writeToBuffer(), FriendlyByteBuf.class, type);
                    m.setAccessible(true);
                    this.writeToBuffer = m;
                    m = ReflectUtil.getAccessibleMethod(clazz, codec.readFromBuffer(), FriendlyByteBuf.class);
                    m.setAccessible(true);
                    this.readFromBuffer = m;
                } else {
                    this.writeToBuffer = null;
                    this.readFromBuffer = null;
                }
                this.valueCodec = null;
                this.streamCodec = null;
            } else {
                try {
                    var f = clazz.getDeclaredField(codec.saveCodec());
                    f.setAccessible(true);
                    this.valueCodec = (ValueCodec) f.get(null);
                    if (!codec.syncCodec().isEmpty()) {
                        f = clazz.getDeclaredField(codec.syncCodec());
                        f.setAccessible(true);
                        this.streamCodec = (StreamCodec) f.get(null);
                    } else {
                        // the network half is then the same id+payload bytes, inline
                        this.streamCodec = ByteBufCodecs.fromValueCodec(valueCodec);
                    }
                    this.writeToValue = null;
                    this.readFromValue = null;
                    this.writeToBuffer = null;
                    this.readFromBuffer = null;
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    boolean isSave() {
        return saveToDisk != null;
    }

    boolean isSyncToClient() {
        return syncToClient != null;
    }

    boolean isSyncToServer() {
        return syncToServer != null;
    }

    boolean hasCustomCodec() {
        return valueCodec != null || streamCodec != null || writeToValue != null || writeToBuffer != null;
    }
}
