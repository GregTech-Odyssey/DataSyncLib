package com.gto.datasynclib.remote;

import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.annotations.RemoteCall;
import com.gto.datasynclib.datastream.codec.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * One scanned {@link RemoteCall} method: its wire name, an adapted {@link MethodHandle} and the
 * {@link StreamCodec} of every parameter, so a call can be written to, and read from, a
 * {@link FriendlyByteBuf} without any per-instance state.
 *
 * <p>The stream codecs are used on purpose: a call travels over the network, so arguments are written
 * straight into the buffer the way the sync path writes its fields — no intermediate value objects and no
 * per-value type tag, which is what the persistence format pays for. A reference parameter is
 * preceded by a present/absent boolean, so {@code null} needs no type tag either; a primitive
 * parameter has no marker and rejects {@code null}.</p>
 *
 * <p>Instances are created by {@link RemoteInvoker} while scanning a class; the scan then numbers them
 * ({@link #wireIndex()}), which is what a payload addresses them by, and they are immutable afterwards.
 * Callers normally only look at the accessors, while the framework uses the package-private
 * {@code writeArgs} / {@code readArgs} / {@code invoke} trio.</p>
 *
 * @see RemoteInvoker#methods(Class)
 */
public final class RemoteMethod {

    /**
     * Up to this many parameters are invoked through an exactly-typed {@code invokeExact} call
     * site; a longer parameter list uses the handle's spreader instead.
     */
    public static final int SPECIALIZED_ARITY = 16;

    private final String name;
    private final Method method;
    private final MethodHandle handle;
    private final MethodHandle spreader;
    private final StreamCodec<? super FriendlyByteBuf, ?>[] codecs;
    private final Class<?>[] parameterTypes;
    private final boolean staticMethod;

    /**
     * The logical side this method belongs to ({@link RemoteCall#side()}), checked when a call is
     * resolved: a call that arrives on the other side is refused before the method runs.
     */
    private final LogicalSide side;

    /**
     * This method's index in the wire table of the class it was scanned for — assigned by
     * {@link RemoteInvoker}'s scan (in wire-name order) and then immutable.
     */
    private int wireIndex;

    RemoteMethod(String name, Method method, MethodHandle handle, StreamCodec<? super FriendlyByteBuf, ?>[] codecs, boolean staticMethod,
                 LogicalSide side) {
        this.name = name;
        this.method = method;
        this.handle = handle;
        // Fallback for a parameter list longer than the specialized switch in invoke(): spreads the
        // array back into the handle's argument list, receiver (slot 0) included.
        this.spreader = codecs.length > 16 ? handle.asSpreader(Object[].class, codecs.length + 1) : null;
        this.codecs = codecs;
        // Cached because Method#getParameterTypes clones its array on every call, and the per-argument
        // null check below runs on every encode/decode.
        this.parameterTypes = method.getParameterTypes();
        this.staticMethod = staticMethod;
        this.side = side;
    }

    /**
     * The logical side this method belongs to — {@link RemoteCall#side()}, or {@link LogicalSide#BOTH}
     * when the annotation does not restrict it.
     */
    public LogicalSide side() {
        return side;
    }

    /**
     * Whether this method may run on {@code actual}, the side a call arrived on: a method declared
     * {@link LogicalSide#BOTH} runs anywhere, and an unknown side ({@code null}, or {@code BOTH} passed
     * as "not known") is never refused.
     *
     * @param actual the side the call arrived on, or {@code null} when it is not known
     */
    public boolean runsOn(@Nullable LogicalSide actual) {
        return actual == null || actual.isBoth() || side.isBoth() || side == actual;
    }

    /**
     * Records where this method sits on the wire. Called once by {@link RemoteInvoker}'s scan, before
     * the entry is published.
     */
    void assignWireIndex(int index) {
        this.wireIndex = index;
    }

    /**
     * This method's index in the wire table of the class it was scanned for, in wire-name order.
     */
    public int wireIndex() {
        return wireIndex;
    }

    /**
     * The wire name of this method — {@link RemoteCall#value()} when set, otherwise the Java
     * method name.
     */
    public String name() {
        return name;
    }

    /**
     * The reflective method this entry was scanned from, useful for diagnostics. Invocation goes
     * through {@link #handle()} so private methods work too.
     */
    public Method method() {
        return method;
    }

    /**
     * The class that declares the method — the class the entry was scanned from, which is the
     * target's class or one of its superclasses.
     */
    public Class<?> declaringClass() {
        return method.getDeclaringClass();
    }

    /**
     * Whether the method is {@code static}: such an entry can be invoked without a target
     * instance ({@code invoke(null, args)}), while an instance method needs the receiving side's
     * target object.
     */
    public boolean isStatic() {
        return staticMethod;
    }

    /**
     * The adapted handle used for invocation.
     */
    public MethodHandle handle() {
        return handle;
    }

    /**
     * The number of parameters, i.e. the number of arguments a call must carry.
     */
    public int parameterCount() {
        return codecs.length;
    }

    /**
     * The stream codec of the parameter at {@code index}, resolved from its declared type — declared
     * for any buffer the call's {@link FriendlyByteBuf} can stand in as, since the registered codec
     * may well be written against the plain {@code ByteBuf}.
     */
    public StreamCodec<? super FriendlyByteBuf, ?> codec(int index) {
        return codecs[index];
    }

    /**
     * Writes the arguments, in declaration order.
     *
     * <p>A reference argument is preceded by a present/absent boolean, so {@code null} round-trips
     * without any type tag; a primitive argument has no marker and rejects {@code null} here, with a
     * message that names the parameter.</p>
     */
    void writeArgs(FriendlyByteBuf buf, @Nullable Object[] args) {
        if (args != null && args.length != codecs.length)
            throw new IllegalArgumentException("Remote method " + name + " expects " + codecs.length + " argument(s), got " + args.length);
        for (int i = 0; i < codecs.length; i++) {
            var value = args == null ? null : args[i];
            if (parameterTypes[i].isPrimitive()) {
                if (value == null)
                    throw new IllegalArgumentException("Remote method " + name + " argument " + i + " is primitive and cannot be null");
                encode(i, buf, value);
            } else if (value == null) {
                buf.writeBoolean(false);
            } else {
                buf.writeBoolean(true);
                encode(i, buf, value);
            }
        }
    }

    /**
     * Reads the arguments written by {@link #writeArgs(FriendlyByteBuf, Object[])}.
     *
     * <p>The returned array carries <strong>one extra leading slot</strong> that {@link #invoke}
     * fills with the receiver; the decoded arguments start at index 1. That slot is what lets the
     * invocation spread the array without copying it.</p>
     */
    @NotNull
    Object[] readArgs(FriendlyByteBuf buf) {
        var args = new Object[codecs.length + 1];
        for (int i = 0; i < codecs.length; i++) {
            if (parameterTypes[i].isPrimitive()) {
                args[i + 1] = decode(i, buf);
            } else {
                args[i + 1] = buf.readBoolean() ? decode(i, buf) : null;
            }
        }
        return args;
    }

    /**
     * Invokes the method on {@code target}. A remote method is always {@code void} (enforced when
     * the class is scanned), so there is no result.
     *
     * <p>Invocation goes through a per-arity {@code invokeExact} call site (up to
     * {@value #SPECIALIZED_ARITY} parameters, which covers every realistic signature), so the JIT
     * sees a monomorphic, exactly-typed call: measured on JDK 21 that is roughly twenty times
     * faster than {@code invokeWithArguments} and within ~2× of a plain Java call. A longer
     * parameter list falls back to the handle's pre-built spreader, which still needs no copy
     * because the receiver travels in slot 0 of the argument array.</p>
     *
     * @param target the receiver, or {@code null} for a static method
     * @param args   an array produced by {@link #readArgs} — slot 0 is overwritten with
     *               {@code target}, the arguments are at indices 1..n
     * @throws IllegalStateException if an instance method is invoked without a target, or if the
     *                               method itself throws — the original failure is the cause
     */
    void invoke(@Nullable Object target, @NotNull Object[] args) {
        if (!staticMethod && target == null)
            throw new IllegalStateException("Remote method " + name + " is an instance method and needs a target");
        args[0] = target;
        try {
            switch (args.length - 1) {
                case 0 -> handle.invokeExact(target);
                case 1 -> handle.invokeExact(target, args[1]);
                case 2 -> handle.invokeExact(target, args[1], args[2]);
                case 3 -> handle.invokeExact(target, args[1], args[2], args[3]);
                case 4 -> handle.invokeExact(target, args[1], args[2], args[3], args[4]);
                case 5 -> handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5]);
                case 6 -> handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6]);
                case 7 -> handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
                case 8 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
                case 9 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
                case 10 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10]);
                case 11 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
                case 12 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12]);
                case 13 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13]);
                case 14 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14]);
                case 15 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15]);
                case 16 ->
                        handle.invokeExact(target, args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16]);
                default -> spreader.invokeExact(args);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Remote method " + name + " threw while invoked", t);
        }
    }

    @SuppressWarnings("unchecked")
    private void encode(int index, FriendlyByteBuf buf, Object value) {
        ((StreamCodec<? super FriendlyByteBuf, Object>) codecs[index]).encode(buf, value);
    }

    @SuppressWarnings("unchecked")
    private Object decode(int index, FriendlyByteBuf buf) {
        return ((StreamCodec<? super FriendlyByteBuf, Object>) codecs[index]).decode(buf);
    }

    @Override
    public String toString() {
        return "RemoteMethod[" + name + " -> " + method + "]";
    }
}
