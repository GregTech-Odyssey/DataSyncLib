package com.gto.datasynclib.field.object;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.datasream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

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
    protected final @NotNull Data write(@NotNull Object source, @NotNull T value) {
        return codec.dataWriter.encode(value);
    }

    @Override
    protected final @NotNull T read(@NotNull Object source, @NotNull Data data, int dataVersion) {
        return codec.dataReader.decode(data, dataVersion);
    }
}
