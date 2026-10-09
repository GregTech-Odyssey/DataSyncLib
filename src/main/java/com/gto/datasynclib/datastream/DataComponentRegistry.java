package com.gto.datasynclib.datastream;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.codec.CombinedCodec;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.StreamCodec;
import com.gto.datasynclib.datastream.codec.ValueCodec;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.util.Registry;
import com.mojang.serialization.Codec;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A typed registry of {@link DataComponentKey} instances that also serves as a codec for
 * {@link DataComponentMap}, enabling component-based serialization across all three codec systems.
 *
 * <p>Implements three codec interfaces simultaneously:
 * <ul>
 *   <li>{@link StreamCodec}{@code <FriendlyByteBuf, DataComponentMap>} — for network sync via {@link FriendlyByteBuf}</li>
 *   <li>{@link com.gto.datasynclib.datastream.codec.ValueCodec}{@code <DataComponentMap>} — for disk
 *       persistence as one carrier string map</li>
 *   <li>{@link Codec}{@code <DataComponentMap>} — for Mojang's DFU codec integration (delegates to {@link Data#CODEC})</li>
 * </ul>
 *
 * <p>During encoding, entries whose keys have a {@code null} codec are silently skipped.
 * Each entry is written with a length prefix so that unknown keys can be safely skipped
 * during decoding without corrupting the buffer position.
 *
 * @see DataComponentKey
 * @see DataComponentMap
 */
public final class DataComponentRegistry extends Registry<String, DataComponentKey<?>> implements CombinedCodec<DataComponentMap> {

    public DataComponentRegistry(String name) {
        // Values (DataComponentKey) carry their String key as a public final 'name' field,
        // so read it directly instead of doing a reverse map lookup.
        super(name + "_data_component", ValueCodec.STRING, key -> key.name);
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
    public DataComponentMap decode(FriendlyByteBuf buf) {
        var size = buf.readVarInt();
        var map = new DataComponentMap(size);
        for (int i = 0; i < size; i++) {
            var keyId = buf.readVarInt();
            var key = get(keyId);
            if (key == null || key.codec == null)
                throw new RuntimeException("Invalid data component key id " + keyId + " {" + key + "}");
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

    // ===== ValueCodec implementation =====
    // A component map is a string map, so the native half is the same map with native values: each
    // entry goes through its key's own codec. The Data pair above stays as the transitional view the
    // component layer still calls.

    @Override
    public Object encode(ValueOps ops, DataComponentMap obj) {
        Map<String, Object> data = new HashMap<>(obj.size());
        obj.fastForEach((k, v) -> {
            if (k.codec != null) data.put(k.name, k.codec.encode(ops, v));
        });
        return ops.createStringMap(data);
    }

    @Override
    public DataComponentMap decode(ValueOps ops, Object d) {
        var data = ops.getStringMap(d);
        var map = new DataComponentMap(data.size());
        data.forEach((k, v) -> {
            var key = get(k);
            if (key == null || key.codec == null) return;
            var value = key.codec.decode(ops, v);
            if (value != null) map.put(key, value);
        });
        return map;
    }
}
