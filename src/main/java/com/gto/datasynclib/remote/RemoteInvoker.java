package com.gto.datasynclib.remote;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.RemoteCall;
import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import com.gto.datasynclib.util.ReflectUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.experimental.UtilityClass;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Entry point and scan cache of the remote call framework: turns a call (method name + arguments)
 * into the {@code byte[]} a packet carries, and turns such a payload back into an invocation.
 *
 * <p>Like {@link com.gto.datasynclib.FieldDefinitionStorage}, this class is a static cache over
 * reflected members — but because a call needs no per-instance state, there is no manager object
 * counterpart to {@code FieldDataManager}: the target object is passed in on both ends
 * ({@link #write(Object, String, Object...)} to encode, {@link #handle(Object, byte[])} to
 * decode and invoke), and the scanned {@link RemoteMethod} table is shared by every instance of
 * the class.</p>
 *
 * <h3>Scan and cache</h3>
 * <p>{@link #methods(Class)} scans the class hierarchy once (superclass first, so an override
 * replaces the inherited entry) for {@link RemoteCall} methods and caches the result per class;
 * methods are keyed by their wire name, which must be unique per class. The cache is a
 * {@link ClassValue}, so it lives on the scanned class instead of in a class-keyed map: nothing
 * pins a mod's classes in memory and the lookup is a direct per-class read. Parameter codecs are
 * resolved at scan time from {@link DataSyncCodec}, so register custom codecs during mod
 * construction — a class scanned before its parameter type was registered fails with a clear
 * message instead of silently encoding nothing. A method that returns a value is rejected there
 * too, because a call has no return channel.</p>
 *
 * <h3>Wire format</h3>
 * <p>A payload is a <strong>call prefix</strong> — the holder-field path to the target as a VarInt depth
 * plus one VarInt per level, then the method's index in the target class's wire table as a VarInt, one
 * to four bytes in practice — followed by the arguments, written with the parameters'
 * <strong>{@link ByteStreamCodec}</strong> halves, exactly like the sync path writes its fields. A call
 * therefore carries no {@code Data} objects, no per-value type tag (the persistence format's cost) and
 * no name string, only the arguments themselves; a reference argument is preceded by a present/absent
 * boolean so {@code null} needs no tag either. The prefix leads the payload so the receiving side can
 * pick the target without touching the arguments, and the whole payload is decoded exactly once.</p>
 *
 * <p>Both numbers are fixed at runtime. The field path is read against
 * {@link RemoteRouting} — index {@code 0} is the first holder field of the addressed object's table, so
 * a holder nested in it writes {@code [0]}, one nested deeper {@code [0, 1]}, and the addressed object
 * itself writes nothing. That is what makes a call land on the exact holder even when several holders
 * serve the same method names, without the receiver searching anything. The method index is the
 * position in the target class's table, which is scanned once and sorted by wire name (reflection hands
 * out declared methods in an unspecified order), so both sides agree on it. The bytes are opaque to the
 * transport and can be carried by any packet — the two built-in ones are
 * {@link RemoteBlockEntityPacket} and {@link RemoteEntityPacket}.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * byte[] payload = RemoteInvoker.write(blockEntity, "requestUpgrade", 3);
 * // ... transport ...
 * RemoteInvoker.handle(blockEntity, payload);
 * }</pre>
 *
 * @see RemoteCall
 * @see RemoteNetwork
 */
@UtilityClass
public class RemoteInvoker {

    private final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * Per-class scan cache. A {@link ClassValue} (rather than a class-keyed map) keeps the entry on
     * the class itself: it cannot pin a mod's classes in memory, and {@code get} needs no map
     * lookup — measured on JDK 21 roughly 2.5 ns per hit against ~9 ns for a
     * {@code ConcurrentHashMap} and ~13 ns for a {@code Reference2ReferenceOpenHashMap}.
     */
    private final ClassValue<Table> CACHE = new ClassValue<>() {
        @Override
        protected Table computeValue(Class<?> type) {
            return scan(type);
        }
    };

    private final Class<?>[] NO_GENERIC_ARGUMENTS = new Class<?>[0];

    /**
     * A class without {@code @RemoteCall} methods shares this table instead of allocating one — the
     * empty case is the common one for plain holders, and an empty fastutil map is not a singleton.
     */
    private final Object2ReferenceOpenHashMap<String, RemoteMethod> NO_METHODS = new Object2ReferenceOpenHashMap<>(0);

    /**
     * The wire table of a class without remote methods — shared with {@link #NO_METHODS}.
     */
    private final RemoteMethod[] NO_METHOD_ARRAY = new RemoteMethod[0];

    /**
     * The remote methods of one class in their wire form: the name → method table and the same methods
     * as an array indexed by wire position.
     *
     * <p>The array is sorted by wire name, which is what makes a wire index mean the same method on
     * every side: reflection hands out declared methods in an unspecified order, so the scan sorts
     * before numbering.</p>
     *
     * @param byName  wire name → method; the concrete fastutil type on purpose, so a lookup on the send
     *                path stays a direct open-addressing probe instead of a {@code Map} interface call
     * @param byIndex the same methods sorted by wire name, so index → method is stable
     */
    private record Table(Object2ReferenceOpenHashMap<String, RemoteMethod> byName, RemoteMethod[] byIndex) {
    }

    /**
     * Returns the cached remote method table of a class, scanning it on first request.
     *
     * <p>The table is keyed by wire name and contains every {@link RemoteCall} method declared by
     * the class or one of its superclasses (an override in a subclass replaces the inherited entry).
     * The very instance is returned — no copy and no unmodifiable wrapper — so treat it as read-only;
     * a class without remote methods returns the shared empty table.</p>
     *
     * @param type the class to look up (usually the target's runtime class)
     * @return the cached name → method table
     */
    public Object2ReferenceOpenHashMap<String, RemoteMethod> methods(Class<?> type) {
        return CACHE.get(type).byName();
    }

    /**
     * Looks up a single remote method.
     *
     * @param type the class to look up
     * @param name the wire name
     * @return the method, or {@code null} if the class has no remote method with that name
     */
    @Nullable
    public RemoteMethod method(Class<?> type, String name) {
        return methods(type).get(name);
    }

    /**
     * Resolves a wire index against a class's table.
     *
     * @param type  the class whose table the index belongs to
     * @param index the wire index from the payload
     * @return the method, or {@code null} when the index is out of range — a payload from a different
     * version of the class, or a malformed one
     */
    @Nullable
    public RemoteMethod methodAt(Class<?> type, int index) {
        var byIndex = CACHE.get(type).byIndex();
        return index >= 0 && index < byIndex.length ? byIndex[index] : null;
    }

    /**
     * Whether a class exposes a remote method with the given wire name.
     */
    public boolean has(Class<?> type, String name) {
        return methods(type).containsKey(name);
    }

    /**
     * Encodes a call to a remote method of {@code target}'s class.
     *
     * @param target the object whose class is scanned for the method — the same object the
     *               receiving side passes to {@link #handle(Object, byte[])}
     * @param method the wire name of the method ({@link RemoteCall#value()} or the Java name)
     * @param args   the arguments, in declaration order; {@code null} is allowed for a reference
     *               parameter
     * @return the serialized call payload, ready to be put into a packet
     * @throws IllegalArgumentException if the class has no such remote method, or the arguments do
     *                                  not match its parameters
     */
    public byte[] write(Object target, String method, Object... args) {
        if (target == null) throw new NullPointerException("Remote call target must not be null");
        return write(target.getClass(), method, args);
    }

    /**
     * Encodes a call to a remote method of {@code owner} — the instance-free form of
     * {@link #write(Object, String, Object...)}, for a {@code static} remote method or when only
     * the class is at hand.
     *
     * @param owner  the class to scan for the method
     * @param method the wire name of the method
     * @param args   the arguments, in declaration order
     * @return the serialized call payload
     */
    public byte[] write(Class<?> owner, String method, Object... args) {
        var remote = method(owner, method);
        if (remote == null)
            throw new IllegalArgumentException("No @RemoteCall method '" + method + "' on " + owner.getName());
        return write(remote, args);
    }

    /**
     * Encodes a call for an already resolved method — the lookup-free form of
     * {@link #write(Object, String, Object...)}, for a caller that has the
     * {@link RemoteMethod} in hand (as {@link com.gto.datasynclib.FieldDataManager} does, so a call
     * costs a single table lookup instead of two).
     *
     * @param remote the resolved method
     * @param args   the arguments, in declaration order
     * @return the serialized call payload, ready to be put into a packet
     * @throws IllegalArgumentException if the arguments do not match the method's parameters
     */
    public byte[] write(RemoteMethod remote, Object... args) {
        return write(remote, RemoteRouting.EMPTY_PATH, args);
    }

    /**
     * Encodes a call for an already resolved method, addressed to a holder at a path — the form a
     * nested holder's manager uses, because it is the one that knows where it sits.
     *
     * @param remote the resolved method
     * @param path   the holder-field path to the target, outermost first; empty for the addressed
     *               object itself
     * @param args   the arguments, in declaration order
     * @return the serialized call payload, ready to be put into a packet
     * @throws IllegalArgumentException if the arguments do not match the method's parameters, or the
     *                                  path is longer than {@link RemoteRouting#MAX_DEPTH}
     */
    public byte[] write(RemoteMethod remote, int[] path, Object... args) {
        if (path.length > RemoteRouting.MAX_DEPTH)
            throw new IllegalArgumentException("Remote call path is " + path.length + " holders deep, but the limit is "
                    + RemoteRouting.MAX_DEPTH + " — a holder tree that deep cannot be addressed");
        var buf = Unpooled.buffer(2 * (path.length + 1) + 16 * (remote.parameterCount() + 1));
        var wrapper = new FriendlyByteBuf(buf);
        try {
            wrapper.writeVarInt(path.length);
            for (var index : path) {
                wrapper.writeVarInt(index);
            }
            wrapper.writeVarInt(remote.wireIndex());
            remote.writeArgs(wrapper, args);
            var bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /**
     * Decodes a call payload and invokes it on {@code target}.
     *
     * <p>The method the payload addresses is resolved on {@code target}'s class hierarchy by table
     * identity and wire index, the arguments are decoded by their parameter codecs and the method is
     * invoked — including a {@code static} one, which ignores the target beyond class resolution. A
     * remote method is {@code void} (see {@link RemoteCall#value()}), so there is no result to hand
     * back: a call is fire-and-forget, and whatever the callee changes is observed through the field
     * synchronization, not a reply.</p>
     *
     * @param target  the object to invoke on (its class resolves the method)
     * @param payload the bytes produced by {@link #write(Object, String, Object...)}; an empty payload
     *                and one whose prefix cannot be read are ignored
     * @throws IllegalArgumentException if no class of the target's hierarchy serves the identity, or the
     *                                  index is out of range — a call from a different version
     * @throws RuntimeException         if the argument section is truncated, or the invoked method
     *                                  itself throws; the original failure is the cause
     */
    public void handle(Object target, byte[] payload) {
        if (target == null) throw new NullPointerException("Remote call target must not be null");
        handle(target.getClass(), target, payload);
    }

    /**
     * Decodes a call payload and invokes it without a target instance — the instance-free form of
     * {@link #handle(Object, byte[])}, for a payload that addresses a {@code static} method of
     * {@code owner} (an instance method fails with {@link IllegalStateException}).
     *
     * @param owner   the class to resolve the method on
     * @param payload the bytes produced by {@link #write(Class, String, Object...)}; an empty
     *                payload is ignored
     */
    public void handle(Class<?> owner, byte[] payload) {
        handle(owner, null, payload);
    }

    /**
     * The method a payload addresses: the holder-field path to the target and the method's index in
     * that target's wire table.
     *
     * <p>The path is empty when the call is for the object the packet addressed. A malformed prefix —
     * an unreadable or over-deep path — reads as {@code null} rather than as a bogus path, so it is
     * ignored instead of walking somewhere unintended.</p>
     *
     * @param path  the holder-field indices, outermost first, empty for the addressed object itself
     * @param index the method's wire index in the target class's table
     */
    public record Route(int[] path, int index) {
    }

    /**
     * Reads the call prefix — field path and method index — leaving {@code payload} positioned at the
     * arguments: the single decode of the prefix, shared by every entry point.
     *
     * @param payload the call payload, read from its current reader index
     * @return the addressed method, or {@code null} if the prefix cannot be read
     */
    @Nullable
    public static Route readRoute(FriendlyByteBuf payload) {
        try {
            var depth = payload.readVarInt();
            if (depth < 0 || depth > RemoteRouting.MAX_DEPTH) return null;
            var path = depth == 0 ? RemoteRouting.EMPTY_PATH : new int[depth];
            for (int i = 0; i < depth; i++) {
                path[i] = payload.readVarInt();
            }
            return new Route(path, payload.readVarInt());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reads only the call prefix out of a call payload — cheap, because it leads the payload and the
     * arguments are not touched (see {@link IFieldDataHolder#handleRemoteCall(Object, byte[])}).
     *
     * @param payload the bytes produced by {@link #write(Object, String, Object...)}
     * @return the addressed method, or {@code null} if the payload is empty or malformed
     */
    @Nullable
    public static Route readRoute(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        var wrapper = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            return readRoute(wrapper);
        } finally {
            wrapper.release();
        }
    }

    private void handle(Class<?> owner, @Nullable Object target, byte[] payload) {
        if (payload == null || payload.length == 0) return;
        var buf = Unpooled.wrappedBuffer(payload);
        try {
            handle(owner, target, new FriendlyByteBuf(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * Walks the call prefix and invokes the method it addresses, decoding the arguments from
     * {@code payload} — the single path every entry point funnels through.
     *
     * <p>The field path is consumed and walked in one pass, so a call allocates nothing on the way to
     * the target: no path array, no route object, no per-level map lookup — one {@link RemoteRouting}
     * table read and one {@link VarHandle} read per level. A prefix that is simply unreadable (empty,
     * truncated, an absurd depth) is ignored, because there is nothing to report it against; a prefix
     * that reads but does not fit the target — an index the class has no field for, an absent holder, a
     * method index out of range, arguments that do not fill the payload — is an error and is reported,
     * since it means the two sides disagree about the class.</p>
     */
    private void handle(Class<?> owner, @Nullable Object target, FriendlyByteBuf payload) {
        var node = target;
        var depth = readPrefixValue(payload);
        if (depth < 0 || depth > RemoteRouting.MAX_DEPTH) return; // unreadable or absurd
        for (int level = 0; level < depth; level++) {
            var fieldIndex = readPrefixValue(payload);
            if (fieldIndex < 0) return; // truncated
            if (node == null)
                throw new IllegalArgumentException("Remote call addresses holder field " + fieldIndex + " below "
                        + owner.getName() + ", which has no instance — pass an instance for a nested target");
            var nodeClass = node.getClass();
            var accessors = RemoteRouting.holderFields(nodeClass);
            var names = RemoteRouting.holderFieldNames(nodeClass);
            if (fieldIndex >= accessors.length)
                throw new IllegalArgumentException("Remote call addresses holder field " + fieldIndex + " of "
                        + nodeClass.getName() + ", which has " + accessors.length + " holder field(s) "
                        + Arrays.toString(names) + " — the call was built against a different class");
            node = accessors[fieldIndex].get(node);
            if (node == null)
                throw new IllegalArgumentException("Remote call addresses holder field " + fieldIndex + " ("
                        + names[fieldIndex] + ") of " + nodeClass.getName() + ", which is absent on this instance");
        }
        var index = readPrefixValue(payload);
        if (index < 0) return; // truncated
        var nodeType = node == null ? owner : node.getClass();
        var remote = methodAt(nodeType, index);
        if (remote == null)
            throw new IllegalArgumentException("No @RemoteCall method with index " + index + " on "
                    + nodeType.getName() + " — the call was built against a different version of that class");
        var args = remote.readArgs(payload);
        if (payload.isReadable())
            throw new IllegalStateException("Remote method " + remote.name() + " on " + nodeType.getName()
                    + " left " + payload.readableBytes() + " byte(s) of the call undecoded — the call was built"
                    + " against a different version of that class");
        remote.invoke(node, args);
    }

    /**
     * Reads one VarInt of a call prefix, returning {@code -1} for an unreadable one — a depth, a field
     * index and a method index are never negative, so the sentinel saves a try block around every read
     * on the receive path.
     */
    private static int readPrefixValue(FriendlyByteBuf payload) {
        try {
            return payload.readVarInt();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Invokes a call on {@code target} whose method is already known, with the buffer positioned at the
     * arguments — for a caller that resolved the method itself (a test, or a packet of your own that
     * carries the method out of band).
     *
     * @param target  the object to invoke on
     * @param method  the wire name of the method
     * @param payload the call payload, positioned at the arguments
     * @throws IllegalArgumentException if the target's class has no such remote method
     */
    public void handle(Object target, String method, FriendlyByteBuf payload) {
        if (target == null) throw new NullPointerException("Remote call target must not be null");
        var remote = method(target.getClass(), method);
        if (remote == null)
            throw new IllegalArgumentException("No @RemoteCall method '" + method + "' on " + target.getClass().getName());
        remote.invoke(target, remote.readArgs(payload));
    }

    /**
     * Invokes the call carried by {@code payload} on {@code target} — the buffer-based form of
     * {@link #handle(Object, byte[])}, for a caller that already has a {@link FriendlyByteBuf}.
     *
     * @param target  the object to invoke on (its class resolves the method)
     * @param payload the call payload, read from its current reader index
     */
    public void handle(Object target, FriendlyByteBuf payload) {
        if (target == null) throw new NullPointerException("Remote call target must not be null");
        handle(target.getClass(), target, payload);
    }

    // ===== Scanning =====

    /**
     * Scans a class and numbers its wire table.
     *
     * <p>The scan collects the annotated methods superclass first (so an override replaces the inherited
     * entry), then the numbering pass sorts the wire names and hands every method the index it has in
     * <em>this</em> class's table — the receiver resolves the index against the target holder's own
     * class, so the two sides only have to agree on that one table.</p>
     */
    private Table scan(Class<?> type) {
        var hierarchy = new ObjectArrayList<Class<?>>(4);
        for (var clazz = type; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            hierarchy.add(clazz);
        }
        // Sized from the hierarchy: only a handful of methods per class are annotated, and a rehash per
        // class would be pure waste on a table that is then never written again.
        var methods = new Object2ReferenceOpenHashMap<String, RemoteMethod>(hierarchy.size() * 4);
        // Superclass first, so an override in a subclass replaces the inherited entry.
        for (int level = hierarchy.size() - 1; level >= 0; level--) {
            var clazz = hierarchy.get(level);
            MethodHandles.Lookup lookup;
            try {
                lookup = MethodHandles.privateLookupIn(clazz, LOOKUP);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access " + clazz.getName() + " for remote call scanning", e);
            }
            for (var method : clazz.getDeclaredMethods()) {
                var annotation = method.getAnnotation(RemoteCall.class);
                if (annotation == null || method.isSynthetic() || method.isBridge()) continue;
                var name = annotation.value().isEmpty() ? method.getName() : annotation.value();
                var remote = create(name, method, lookup);
                var previous = methods.put(name, remote);
                if (previous != null && !overrides(previous, method))
                    throw new IllegalStateException("Duplicate remote method name '" + name + "' on " + type.getName()
                            + ": " + previous.method() + " and " + method
                            + " — give one of them a distinct @RemoteCall(\"...\") name");
            }
        }
        if (methods.isEmpty()) return new Table(NO_METHODS, NO_METHOD_ARRAY);
        var byIndex = methods.values().toArray(new RemoteMethod[0]);
        Arrays.sort(byIndex, Comparator.comparing(RemoteMethod::name));
        for (int index = 0; index < byIndex.length; index++) {
            byIndex[index].assignWireIndex(index);
        }
        return new Table(methods, byIndex);
    }

    /**
     * Whether {@code candidate} overrides the method behind {@code previous} — same Java name and
     * parameter types, which is how an annotated override is allowed to shadow the inherited entry.
     */
    private boolean overrides(RemoteMethod previous, Method candidate) {
        var inherited = previous.method();
        return inherited.getName().equals(candidate.getName())
                && Arrays.equals(inherited.getParameterTypes(), candidate.getParameterTypes());
    }

    private RemoteMethod create(String name, Method method, MethodHandles.Lookup lookup) {
        if (method.getReturnType() != void.class)
            throw new IllegalStateException("Remote method " + method.getDeclaringClass().getName() + "." + method.getName()
                    + " returns " + method.getReturnType().getSimpleName()
                    + ", but a remote call has no return channel — declare it void and observe the effect through field synchronization");
        var parameterTypes = method.getParameterTypes();
        var genericTypes = method.getGenericParameterTypes();
        var codecs = new ByteStreamCodec<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            codecs[i] = codecFor(method, i, parameterTypes[i], genericTypes[i]);
        }
        return new RemoteMethod(name, method, invokerHandle(lookup, method), codecs,
                Modifier.isStatic(method.getModifiers()));
    }

    /**
     * Builds the uniform invocation handle: {@code (Object receiver, Object... args) -> void}.
     *
     * <p>The direct handle is adapted with {@link MethodHandles#explicitCastArguments} rather than
     * {@code asType}, because the cast is what boxes and unboxes the primitive arguments against
     * the {@code Object} slots. A static method has no receiver slot in its handle, so a discarded
     * one is dropped in front of it, keeping the shape uniform — which is what lets one
     * {@code invokeExact} call site serve every method.</p>
     */
    private MethodHandle invokerHandle(MethodHandles.Lookup lookup, Method method) {
        var isStatic = Modifier.isStatic(method.getModifiers());
        var parameters = new Class<?>[method.getParameterCount() + (isStatic ? 0 : 1)];
        Arrays.fill(parameters, Object.class);
        try {
            var handle = MethodHandles.explicitCastArguments(ReflectUtil.createDirectMethodHandle(lookup, method),
                    MethodType.methodType(void.class, parameters));
            return isStatic ? MethodHandles.dropArguments(handle, 0, Object.class) : handle;
        } catch (Throwable t) {
            throw new IllegalStateException("Cannot build an invocation handle for remote method "
                    + method.getDeclaringClass().getName() + "." + method.getName(), t);
        }
    }

    /**
     * Resolves the stream codec of one parameter: the registry is asked for the type (with its type
     * arguments when the parameter is parameterized, exactly like a field's codec, and with a primitive
     * resolved as its wrapper) and the network half is taken from it — the persist half is never
     * touched by a call. A parameter with neither half registered, or one whose codec cannot be
     * narrowed to the stream side, fails at scan time with a message naming the parameter.
     */
    private ByteStreamCodec<?> codecFor(Method method, int index, Class<?> type, Type genericType) {
        Class<?>[] genericArguments;
        try {
            genericArguments = ReflectUtil.getFieldGenericTypeClasses(genericType);
        } catch (Throwable ignored) {
            genericArguments = NO_GENERIC_ARGUMENTS;
        }
        var codec = genericArguments.length == 0 ? DataSyncCodec.get(type) : DataSyncCodec.get(type, genericArguments);
        if (codec == null)
            throw new IllegalStateException("No DataSyncCodec registered for parameter " + index + " ("
                    + type.getSimpleName() + ") of remote method " + method.getDeclaringClass().getSimpleName()
                    + "." + method.getName() + " — register one during mod construction");
        return codec.toStreamCodec();
    }
}
