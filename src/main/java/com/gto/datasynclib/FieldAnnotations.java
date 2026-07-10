package com.gto.datasynclib;

import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.datasream.codec.ByteStreamCodec;
import com.gto.datasynclib.datasream.codec.DataCodec;
import com.gto.datasynclib.datasream.data.Data;
import com.gto.datasynclib.util.ReflectUtil;
import it.unimi.dsi.fastutil.Hash;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Getter
@Accessors(fluent = true)
final class FieldAnnotations {

    private final SaveToDisk saveToDisk;
    private final SyncToClient syncToClient;
    private final SyncToServer syncToServer;

    private final String key;
    private final boolean saveNull;
    private final Object defaultValue;
    private final Method defaultValueGetter;
    private final Method clientUpdateListener;
    private final Method serverUpdateListener;
    private final Method saveConditions;
    private final Method syncToClientConditions;
    private final Method syncToServerConditions;
    private final Hash.Strategy strategy;
    private final ByteStreamCodec streamCodec;
    private final DataCodec dataCodec;
    private final Method writeToData;
    private final Method readFromData;
    private final Method writeToBuffer;
    private final Method readFromBuffer;
    private final boolean notifyClientUpdate;
    private final boolean notifyServerUpdate;
    private final boolean autoServerUpdate;
    private final boolean autoClientUpdate;
    private final boolean generic;
    private final boolean access;
    private final boolean createAccessInstance;

    FieldAnnotations(Class<?> clazz, Field field, SaveToDisk saveToDisk, SyncToClient syncToClient, SyncToServer syncToServer) {
        var type = field.getType();
        this.generic = field.getAnnotation(Generic.class) != null;
        this.access = field.getAnnotation(Access.class) != null;
        this.saveToDisk = saveToDisk;
        this.syncToClient = syncToClient;
        this.syncToServer = syncToServer;
        this.key = saveToDisk == null || saveToDisk.key().isEmpty() ? field.getName() : saveToDisk.key();
        this.saveNull = saveToDisk == null || saveToDisk.saveNull();
        this.defaultValue = saveToDisk == null ? null : ReflectUtil.parse(type, saveToDisk.defaultValue());
        this.notifyClientUpdate = syncToClient != null && syncToClient.notifyUpdate();
        this.notifyServerUpdate = syncToServer != null && syncToServer.notifyUpdate();
        this.autoServerUpdate = syncToClient != null && syncToClient.autoUpdate();
        this.autoClientUpdate = syncToServer != null && syncToServer.autoUpdate();
        this.createAccessInstance = access && field.getAnnotation(Access.class).createInstance();

        if (saveToDisk != null && !saveToDisk.defaultValueGetter().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, saveToDisk.defaultValueGetter());
            method.setAccessible(true);
            this.defaultValueGetter = method;
        } else {
            this.defaultValueGetter = null;
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

        if (saveToDisk != null && !saveToDisk.conditions().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, saveToDisk.conditions(), type);
            method.setAccessible(true);
            this.saveConditions = method;
        } else {
            this.saveConditions = null;
        }

        if (syncToClient != null && !syncToClient.conditions().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, syncToClient.conditions(), type);
            method.setAccessible(true);
            this.syncToClientConditions = method;
        } else {
            this.syncToClientConditions = null;
        }

        if (syncToServer != null && !syncToServer.conditions().isEmpty()) {
            var method = ReflectUtil.getAccessibleMethod(clazz, syncToServer.conditions(), type);
            method.setAccessible(true);
            this.syncToServerConditions = method;
        } else {
            this.syncToServerConditions = null;
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
}
