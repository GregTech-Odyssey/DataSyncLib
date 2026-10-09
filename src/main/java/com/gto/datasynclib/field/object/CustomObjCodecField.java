package com.gto.datasynclib.field.object;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Object field that uses a provided {@link com.gto.datasynclib.DataSyncCodec} for all
 * serialization and deserialization, independent of the field definition's own codec.
 */
public class CustomObjCodecField<T> extends ObjField<T> {

    private final DataSyncCodec<T> codec;

    public CustomObjCodecField(DataFieldDefinition<T> definition, DataSyncCodec<T> codec) {
        super(definition);
        this.codec = codec;
    }

    @Override
    protected final void write(@NotNull Object source, @NotNull FriendlyByteBuf data, @NotNull T value) {
        codec.streamWriter.encode(data, value);
    }

    @Override
    protected final @NotNull T read(@NotNull Object source, @NotNull FriendlyByteBuf data) {
        return codec.streamReader.decode(data);
    }

    @Override
    protected final @NotNull Object write(@NotNull Object source, @NotNull T value, @NotNull ValueOps ops) {
        return codec.encode(ops, value);
    }

    @Override
    protected final @NotNull T read(@NotNull Object source, @NotNull Object data, @NotNull ValueOps ops) {
        return codec.decode(ops, data);
    }
}
