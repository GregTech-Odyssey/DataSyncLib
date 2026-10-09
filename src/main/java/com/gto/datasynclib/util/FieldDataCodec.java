package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.ValueOps;
import com.gto.datasynclib.*;
import com.gto.datasynclib.datastream.codec.*;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.function.Supplier;

/**
 * A composite codec that bridges {@link com.gto.datasynclib.FieldDataManager} with
 * the {@link com.gto.datasynclib.datastream.codec.StreamCodec} and
 * {@link com.gto.datasynclib.datastream.codec.ValueCodec} interface.
 *
 * <p>This class wraps an annotated POJO class, using its own internal
 * {@link com.gto.datasynclib.FieldDataManager} to serialize/deserialize <em>all</em> managed
 * fields as a single unit — fields carrying {@code @SaveToDisk}/{@code @SyncToClient}/
 * {@code @SyncToServer}, plus plain {@code @AddToManager} fields (the codec uses
 * {@code writeAllToData}/{@code readAllFromData}, not the incremental paths). It implements
 * {@link com.gto.datasynclib.IFieldDataHolder} so that the codec methods can
 * set the current instance via a {@link ThreadLocal} during encode/decode,
 * making each thread's encode/decode operations independent.</p>
 *
 * <p>Create instances via
 * {@link com.gto.datasynclib.FieldDataManager#createCodec(Class, java.util.function.Supplier)}.</p>
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 * private static final FieldDataCodec<MyData> MY_CODEC =
 *     FieldDataManager.createCodec(MyData.class, MyData::new);
 *
 * // Then reference in @Codec annotation:
 * @Codec(saveCodec = "MY_CODEC", syncCodec = "MY_CODEC")
 * private MyData data;
 * }</pre>
 *
 * <h3>Thread-safety:</h3>
 * This class uses a {@link ThreadLocal} to isolate the current instance during
 * encode/decode operations. Concurrent calls from different threads are safe;
 * however, concurrent calls on the <em>same</em> thread (e.g., nested encode/decode)
 * will overwrite the ThreadLocal value, which may cause data corruption if the
 * outer operation continues after the inner one completes.
 *
 * @param <T> the POJO type this codec can encode and decode
 * @see com.gto.datasynclib.FieldDataManager#createCodec(Class, java.util.function.Supplier)
 */
public class FieldDataCodec<T> implements CombinedCodec<T>, IFieldDataHolder {

    private final Supplier<T> constructor;
    private final LazyFieldDataManager fieldDataManager;

    private final StreamEncoder<FriendlyByteBuf, ? super T> streamWriter;
    private final StreamDecoder<FriendlyByteBuf, ? extends T> streamReader;

    private final ThreadLocal<T> currentInstance = new ThreadLocal<>();

    public FieldDataCodec(Class<T> objClass, Supplier<T> constructor, StreamEncoder<FriendlyByteBuf, ? super T> extraStreamWriter, StreamDecoder<FriendlyByteBuf, ? extends T> extraStreamReader) {
        this.constructor = constructor;
        this.fieldDataManager = new LazyFieldDataManager(this, objClass);
        this.streamWriter = extraStreamWriter;
        this.streamReader = extraStreamReader;
    }

    public FieldDataCodec(Class<T> objClass, Supplier<T> constructor) {
        this(objClass, constructor, null, null);
    }

    @Override
    public FieldDataManager getFieldDataManager() {
        return fieldDataManager.get();
    }

    @Override
    public Object getSource(DataFieldDefinition<?> definition) {
        return currentInstance.get();
    }

    @Override
    public void writeCustomSyncData(FriendlyByteBuf buf, boolean writeAll) {
        if (streamWriter != null) streamWriter.encode(buf, currentInstance.get());
    }

    @Override
    public void readCustomSyncData(FriendlyByteBuf buf) {
        if (streamReader != null) streamReader.decode(buf);
    }

    // A FieldDataCodec is a holder only so the codec can reach its own manager; the manager never
    // walks it as a nested holder of another object, so there is no custom entry to add either way.
    @Override
    public void writeCustomSaveData(Map<String, Object> data, ValueOps ops) {
    }

    @Override
    public void readCustomSaveData(Map<String, Object> data, ValueOps ops) {
    }

    @Override
    public T decode(FriendlyByteBuf buf) {
        var obj = constructor.get();
        currentInstance.set(obj);
        try {
            fieldDataManager.get().readFromNetworkBuffer(LogicalSide.BOTH, buf.readByteArray());
        } finally {
            currentInstance.remove();
        }
        return obj;
    }

    @Override
    public void encode(FriendlyByteBuf buf, T obj) {
        currentInstance.set(obj);
        try {
            buf.writeByteArray(fieldDataManager.get().writeToNetworkBuffer(LogicalSide.BOTH, true));
        } finally {
            currentInstance.remove();
        }
    }

    // ===== ValueCodec implementation — the object as one carrier string map =====

    @Override
    public Object encode(ValueOps ops, T obj) {
        currentInstance.set(obj);
        try {
            return fieldDataManager.get().writeAllToValue(ops);
        } finally {
            currentInstance.remove();
        }
    }

    @Override
    public T decode(ValueOps ops, Object data) {
        var obj = constructor.get();
        currentInstance.set(obj);
        try {
            fieldDataManager.get().readAllFromValue(data, ops);
        } finally {
            currentInstance.remove();
        }
        return obj;
    }
}
