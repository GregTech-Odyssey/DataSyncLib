package com.gto.datasynclib;

import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
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
    private final ByteStreamCodec streamCodec;
    private final DataCodec dataCodec;
    private final Method writeToData;
    private final Method readFromData;
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
        // DataFieldDefinition; the readFromData implementations invoke it after a disk load.
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
            this.dataCodec = null;
            this.streamCodec = null;
            this.writeToData = null;
            this.readFromData = null;
            this.writeToBuffer = null;
            this.readFromBuffer = null;
        } else {
            if (codec.saveCodec().isEmpty()) {
                if (!codec.writeToData().isEmpty()) {
                    var m = ReflectUtil.getAccessibleMethod(clazz, codec.writeToData(), type);
                    m.setAccessible(true);
                    this.writeToData = m;
                    m = ReflectUtil.getAccessibleMethod(clazz, codec.readFromData(), Data.class, int.class);
                    m.setAccessible(true);
                    this.readFromData = m;
                } else {
                    this.writeToData = null;
                    this.readFromData = null;
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
                this.dataCodec = null;
                this.streamCodec = null;
            } else {
                try {
                    var f = clazz.getDeclaredField(codec.saveCodec());
                    f.setAccessible(true);
                    this.dataCodec = (DataCodec) f.get(null);
                    if (!codec.syncCodec().isEmpty()) {
                        f = clazz.getDeclaredField(codec.syncCodec());
                        f.setAccessible(true);
                        this.streamCodec = (ByteStreamCodec) f.get(null);
                    } else {
                        this.streamCodec = ByteStreamCodec.of(dataCodec);
                    }
                    this.writeToData = null;
                    this.readFromData = null;
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
        return dataCodec != null || streamCodec != null || writeToData != null || writeToBuffer != null;
    }
}
