package com.gto.datasynclib;

import com.gto.datasynclib.datasream.data.Data;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

public interface DataField<T> {

    DataFieldDefinition<T> getDefinition();

    void markAsChanged(@NotNull Object source);

    boolean isChanged(@NotNull Object source);

    void clearChanged(@NotNull Object source);

    boolean detectChange(@NotNull LogicalSide side, @NotNull Object source, boolean auto);

    void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean force);

    void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data);

    @NotNull
    Data writeToData(@NotNull Object source);

    void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion);

    default boolean mustDetected() {
        return false;
    }

    interface Factory<T> {

        @SuppressWarnings("rawtypes")
        DataField<T> create(DataFieldDefinition definition);
    }

    record CustomFactory<T>(Predicate<Class<?>> predicate, Function<Class<?>, Factory<T>> factory, int priority) {

        static final Comparator<CustomFactory<?>> COMPARATOR = Comparator.comparingInt(f -> -f.priority);
    }

    record CustomGenericFactory<T>(BiPredicate<Class<?>, Class<?>[]> predicate,
                                   BiFunction<Class<?>, Class<?>[], Factory<T>> factory, int priority) {

        static final Comparator<CustomGenericFactory<?>> COMPARATOR = Comparator.comparingInt(f -> -f.priority);
    }
}
