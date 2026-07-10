package com.gto.datasynclib.field.object;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.datastream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Object field that delegates serialization to the DataFieldDefinition's built-in encode/decode
 * methods.
 */
public class ObjCodecField<T> extends ObjField<T> {

    public ObjCodecField(DataFieldDefinition<T> definition) {
        super(definition);
    }

    @Override
    protected final void write(@NotNull Object source, @NotNull FriendlyByteBuf data, @NotNull T value) {
        definition.encode(source, value, data);
    }

    @Override
    protected final @NotNull T read(@NotNull Object source, @NotNull FriendlyByteBuf data) {
        return definition.decode(source, data);
    }

    @Override
    protected final @NotNull Data write(@NotNull Object source, @NotNull T value) {
        return definition.encode(source, value);
    }

    @Override
    protected final @NotNull T read(@NotNull Object source, @NotNull Data data, int dataVersion) {
        return definition.decode(source, data, dataVersion);
    }
}
