package com.gto.datasynclib.datastream;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.util.Registry;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A typed registry of {@link DataComponentKey} instances that also serves as a codec for
 * {@link DataComponentMap}, enabling component-based serialization across all three codec systems.
 *
 * <p>Implements three codec interfaces simultaneously:
 * <ul>
 *   <li>{@link ByteStreamCodec}{@code <DataComponentMap>} — for network sync via {@link FriendlyByteBuf}</li>
 *   <li>{@link DataCodec}{@code <DataComponentMap>} — for disk persistence via {@link com.gto.datasynclib.datastream.data.StringMapData}</li>
 *   <li>{@link Codec}{@code <DataComponentMap>} — for Mojang's DFU codec integration (delegates to {@link Data#CODEC})</li>
 * </ul>
 *
 * <p>During encoding, entries whose keys have a {@code null} codec are silently skipped.
 * During decoding, entries with unknown keys or null codecs are skipped (the buffer position
 * is not advanced, so remaining entries may be affected).
 *
 * @see DataComponentKey
 * @see DataComponentMap
 */
public final class DataComponentRegistry extends Registry<String, DataComponentKey<?>> implements ByteStreamCodec<DataComponentMap>, DataCodec<DataComponentMap>, Codec<DataComponentMap> {

    public DataComponentRegistry(String name) {
        super(name + "_data_component");
    }

    public <T> DataComponentKey<T> register(String name, DataSyncCodec<T> codec) {
        return register(new DataComponentKey<>(name, codec));
    }

    public <T> DataComponentKey<T> register(String name, Consumer<DataComponentKey.Builder<T>> builder) {
        return register(DataComponentKey.create(name, builder));
    }

    public <T> DataComponentKey<T> register(DataComponentKey<T> key) {
        return super.register(key.name, key);
    }

    public <T extends DataComponentKey<?>> T getDataComponentKey(String name) {
        return (T) super.get(name);
    }

    public <T extends DataComponentKey<?>> T getDataComponentKey(int id) {
        return (T) super.get(id);
    }

    @Override
    public <T> DataResult<Pair<DataComponentMap, T>> decode(DynamicOps<T> ops, T input) {
        return Data.CODEC.map(this::decode).decode(ops, input);
    }

    @Override
    public <T> DataResult<T> encode(DataComponentMap input, DynamicOps<T> ops, T prefix) {
        return Data.CODEC.comap(i -> encode(input)).encode(input, ops, prefix);
    }

    @Override
    public DataComponentMap decode(FriendlyByteBuf buf) {
        var size = buf.readVarInt();
        var map = new DataComponentMap(size);
        for (int i = 0; i < size; i++) {
            var keyId = buf.readVarInt();
            var key = get(keyId);
            if (key == null || key.codec == null) {
                // Cannot decode value without a registered key with a codec.
                // The buffer is now corrupted — skip remaining entries.
                break;
            }
            var value = key.codec.streamReader.decode(buf);
            if (value != null) map.put(key, value);
        }
        return map;
    }

    @Override
    public void encode(FriendlyByteBuf buf, DataComponentMap obj) {
        buf.writeVarInt(obj.size());
        obj.fastForEach((k, v) -> {
            if (k.codec == null) return; // skip entries without a registered codec
            buf.writeVarInt(getId(k));
            k.codec.streamWriter.encode(buf, v);
        });
    }

    @Override
    public DataComponentMap decode(@NotNull Data d, int dataVersion) {
        var data = d.getStringMap();
        var map = new DataComponentMap(data.size());
        data.forEach((k, v) -> {
            var key = get(k);
            if (key == null || key.codec == null) return;
            var value = key.codec.dataReader.decode(v, dataVersion);
            if (value != null) map.put(key, value);
        });
        return map;
    }

    @Override
    public @NotNull Data encode(DataComponentMap obj) {
        var data = new StringMapData();
        obj.fastForEach((k, v) -> {
            if (k.codec != null) data.put(k.name, k.codec.dataWriter.encode(v));
        });
        return data;
    }
}
