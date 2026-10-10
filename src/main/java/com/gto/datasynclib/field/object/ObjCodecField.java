package com.gto.datasynclib.field.object;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.datastream.codec.ValueOps;
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
    protected final @NotNull Object write(@NotNull Object source, @NotNull T value, @NotNull ValueOps ops) {
        return definition.encode(source, value, ops);
    }

    @Override
    protected final @NotNull T read(@NotNull Object source, @NotNull Object data, @NotNull ValueOps ops) {
        return definition.decode(source, data, ops);
    }
}
