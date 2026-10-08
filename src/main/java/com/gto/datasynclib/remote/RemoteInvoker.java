package com.gto.datasynclib.remote;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.annotations.RemoteCall;
import com.gto.datasynclib.datastream.codec.StreamCodec;
import com.gto.datasynclib.util.ReflectUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.experimental.UtilityClass;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
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
 * <p>{@link #methods(Class)} scans the type hierarchy once for {@link RemoteCall} methods and caches
 * the result per class; methods are keyed by their wire name, which must be unique per class. The
 * hierarchy is the superclass chain plus every interface those classes implement, transitively, most
 * specific first — an annotated {@code default} method of a feature interface is part of the contract,
 * and Java's own precedence decides which declaration of a name wins (a class method beats an interface
 * default, a subclass beats its superclass). The cache is a
 * {@link ClassValue}, so it lives on the scanned class instead of in a class-keyed map: nothing
 * pins a mod's classes in memory and the lookup is a direct per-class read. Parameter codecs are
 * resolved at scan time from {@link DataSyncCodec}, so register custom codecs during mod
 * construction — a class scanned before its parameter type was registered fails with a clear
 * message instead of silently encoding nothing. A method that returns a value is rejected there
 * too, because a call has no return channel.</p>
 *
 * <h3>Wire format</h3>
 * <p>A payload is a <strong>call prefix</strong> of two VarInts — the target's index in the addressed
 * object's flattened holder tree ({@link RemoteRouting}, {@code 0} being that object itself) and the
 * method's index in the target class's wire table — followed by the arguments, written with the
 * parameters' <strong>{@link StreamCodec}</strong> halves, exactly like the sync path writes its
 * fields. A call therefore carries no {@code Data} objects, no per-value type tag (the persistence
 * format's cost) and no name string, only the arguments themselves; a reference argument is preceded by
 * a present/absent boolean so {@code null} needs no tag either. The prefix leads the payload so the
 * receiving side can pick the target without touching the arguments, and the whole payload is decoded
 * exactly once — usually two bytes plus the arguments, whatever the depth of the tree.</p>
 *
 * <p>Both indexes are fixed at runtime. The holder tree of a class is flattened once and cached, in
 * superclass-first, field-name-sorted, depth-first order, so index {@code n} is the same node on both
 * sides and resolving it on the receiving side is a few field reads along one cached path — nothing is
 * searched. The method index is the position in the target class's table, which is likewise scanned once
 * and sorted by wire name (reflection hands out declared methods in an unspecified order). The bytes are
 * opaque to the transport and can be carried by any packet — the two built-in ones are
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
     * costs a single table lookup instead of two). The call is addressed to the packet target itself.
     *
     * <p>The arguments are taken as the array itself rather than as varargs: the indexed form below
     * takes an {@code int} in the same position, and a varargs {@code Object...} here would make every
     * call with a primitive argument ({@code write(remote, index, 5)}) ambiguous.</p>
     *
     * @param remote the resolved method
     * @param args   the arguments, in declaration order, or {@code null} for none
     * @return the serialized call payload, ready to be put into a packet
     * @throws IllegalArgumentException if the arguments do not match the method's parameters
     */
    public byte[] write(RemoteMethod remote, Object[] args) {
        return write(remote, 0, args);
    }

    /**
     * Encodes a call for an already resolved method, addressed to a node of the tree the packet will
     * target.
     *
     * @param remote      the resolved method
     * @param holderIndex the target's index in the flattened tree of the object the packet addresses
     *                    ({@link RemoteRouting#indexOf(Object, Object)}), {@code 0} for that object
     *                    itself
     * @param args        the arguments, in declaration order
     * @return the serialized call payload, ready to be put into a packet
     * @throws IllegalArgumentException if the arguments do not match the method's parameters
     */
    public byte[] write(RemoteMethod remote, int holderIndex, Object... args) {
        var buf = Unpooled.buffer(4 + 16 * (remote.parameterCount() + 1));
        var wrapper = new FriendlyByteBuf(buf);
        try {
            wrapper.writeVarInt(holderIndex);
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
        handle(target.getClass(), target, sideOf(target), payload);
    }

    /**
     * Decodes a call payload and invokes it on {@code target}, telling the framework which logical side
     * the call arrived on — the form a packet handler uses when it knows the side itself.
     *
     * <p>The side is only used to enforce {@link RemoteCall#side()}: a method declared for one side is
     * refused (with a clear message) when the call arrives on the other. Passing {@code null} — or the
     * two-argument form, which derives the side from the target's level — means "not known", and no
     * method is refused then.</p>
     *
     * @param target  the object to invoke on (its class resolves the method)
     * @param payload the call payload
     * @param side    the side the call arrived on, or {@code null} when it is not known
     */
    public void handle(Object target, byte[] payload, @Nullable LogicalSide side) {
        if (target == null) throw new NullPointerException("Remote call target must not be null");
        handle(target.getClass(), target, side, payload);
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
        handle(owner, null, null, payload);
    }

    /**
     * The instance-free form of {@link #handle(Object, byte[], LogicalSide)}.
     *
     * @param owner   the class to resolve the method on
     * @param payload the call payload
     * @param side    the side the call arrived on, or {@code null} when it is not known
     */
    public void handle(Class<?> owner, byte[] payload, @Nullable LogicalSide side) {
        handle(owner, null, side, payload);
    }

    /**
     * The logical side {@code target} lives on, taken from its level — {@code null} when it is not known
     * (an object that is not a block entity or entity, or one that is not in a level at all, as in a unit
     * test).
     *
     * <p>This is what the two-argument {@code handle} forms use to enforce {@link RemoteCall#side()}; a
     * packet handler that already knows the side can pass it explicitly instead.</p>
     */
    @Nullable
    public static LogicalSide sideOf(Object target) {
        var level = target instanceof BlockEntity blockEntity ? blockEntity.getLevel()
                : target instanceof Entity entity ? entity.level() : null;
        if (level == null) return null;
        return level.isClientSide() ? LogicalSide.CLIENT : LogicalSide.SERVER;
    }

    /**
     * The method a payload addresses: the target's index in the flattened holder tree of the object the
     * packet addressed, and the method's index in that target's wire table.
     *
     * <p>Index {@code 0} means the addressed object itself. A prefix that cannot be read at all reads as
     * {@code null}, so it is ignored instead of resolving somewhere unintended.</p>
     *
     * @param holder the target's index in the addressed object's holder tree
     * @param method the method's wire index in the target class's table
     */
    public record Route(int holder, int method) {
    }

    /**
     * Reads the call prefix — holder index and method index — leaving {@code payload} positioned at the
     * arguments: the single decode of the prefix, shared by every entry point.
     *
     * @param payload the call payload, read from its current reader index
     * @return the addressed method, or {@code null} if the prefix cannot be read
     */
    @Nullable
    public static Route readRoute(FriendlyByteBuf payload) {
        try {
            var holder = payload.readVarInt();
            var method = payload.readVarInt();
            return holder < 0 || method < 0 ? null : new Route(holder, method);
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

    private void handle(Class<?> owner, @Nullable Object target, @Nullable LogicalSide side, byte[] payload) {
        if (payload == null || payload.length == 0) return;
        var buf = Unpooled.wrappedBuffer(payload);
        try {
            handle(owner, target, side, new FriendlyByteBuf(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * Resolves the call prefix against the packet target and invokes the method it addresses, decoding
     * the arguments from {@code payload} — the single path every entry point funnels through.
     *
     * <p>Resolving a call is one array read and a few {@code VarHandle} reads along the cached
     * {@link RemoteRouting} path of that index: the target's whole tree was flattened once per class, so
     * nothing is walked, searched or built per call. A prefix that is simply unreadable (empty,
     * truncated) is ignored, because there is nothing to report it against; a prefix that reads but does
     * not fit this tree — an index it has no node for, a node that is absent on this instance, a method
     * index out of range, arguments that do not fill the payload, a method declared for the other
     * {@link RemoteCall#side() side} — is an error and is reported, since it means the two sides disagree
     * about the class or the call was addressed to the wrong side.</p>
     */
    private void handle(Class<?> owner, @Nullable Object target, @Nullable LogicalSide side, FriendlyByteBuf payload) {
        var holderIndex = readPrefixValue(payload);
        if (holderIndex < 0) return; // unreadable
        var methodIndex = readPrefixValue(payload);
        if (methodIndex < 0) return; // truncated
        Object node;
        if (target == null) {
            // Instance-free (static) call: index 0 is the class itself, and anything else needs an object.
            if (holderIndex != 0)
                throw new IllegalArgumentException("A static remote call cannot address holder index " + holderIndex
                        + " of " + owner.getName() + " — pass an instance");
            node = null;
        } else {
            node = RemoteRouting.nodeAt(target, holderIndex);
            if (node == null)
                throw new IllegalArgumentException("Remote call addresses holder index " + holderIndex + " of "
                        + target.getClass().getName() + ", which has " + RemoteRouting.holderNodeCount(target.getClass())
                        + " node(s) " + Arrays.toString(RemoteRouting.holderNodeNames(target.getClass()))
                        + " — the call was built against a different tree, or the holder is absent on this instance");
        }
        var nodeType = node == null ? owner : node.getClass();
        var remote = methodAt(nodeType, methodIndex);
        if (remote == null)
            throw new IllegalArgumentException("No @RemoteCall method with index " + methodIndex + " on "
                    + nodeType.getName() + " — the call was built against a different version of that class");
        if (!remote.runsOn(side))
            throw new IllegalArgumentException("Remote method " + remote.name() + " on " + nodeType.getName()
                    + " is declared for " + remote.side() + " but the call arrived on " + side
                    + " — send it the other way, or widen @RemoteCall(side = ...)");
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
        handle(target.getClass(), target, sideOf(target), payload);
    }

    // ===== Scanning =====

    /**
     * Scans a class and numbers its wire table.
     *
     * <p>The scan collects the annotated methods of the whole type hierarchy — every superclass and
     * every interface it implements, transitively — then the numbering pass sorts the wire names and
     * hands every method the index it has in <em>this</em> class's table: the receiver resolves the
     * index against the target holder's own class, so the two sides only have to agree on that one
     * table.</p>
     *
     * <p>Interfaces are part of that hierarchy because an annotated {@code default} method is as much
     * part of a holder's remote contract as a method declared on the class: a feature interface can
     * declare {@code @RemoteCall default void onSomethingClient(...)} and every implementor answers it.
     * Precedence follows Java's own: the most specific declaration of a name wins, so a class method
     * (declared or inherited) beats an interface default, a subclass beats its superclass, and a
     * sub-interface beats its super-interface.</p>
     */
    private Table scan(Class<?> type) {
        // Most specific first; walked in reverse below so the most specific declaration of a name is the
        // one that stays in the table.
        var hierarchy = new ObjectArrayList<Class<?>>(8);
        collectHierarchy(type, hierarchy);
        // Sized from the hierarchy: only a handful of methods per class are annotated, and a rehash per
        // class would be pure waste on a table that is then never written again.
        var methods = new Object2ReferenceOpenHashMap<String, RemoteMethod>(hierarchy.size() * 4);
        for (int level = hierarchy.size() - 1; level >= 0; level--) {
            var clazz = hierarchy.get(level);
            // Resolved only for a type that actually declares an annotated method: a hierarchy routinely
            // contains interfaces of the JDK (String implements CharSequence, Comparable, …), whose
            // packages a mod cannot open, and reading their methods for annotations needs no lookup.
            MethodHandles.Lookup lookup = null;
            for (var method : clazz.getDeclaredMethods()) {
                var annotation = method.getAnnotation(RemoteCall.class);
                if (annotation == null || method.isSynthetic() || method.isBridge()) continue;
                if (lookup == null) lookup = lookupFor(clazz);
                var name = annotation.value().isEmpty() ? method.getName() : annotation.value();
                var remote = create(name, method, lookup, annotation.side());
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
     * The lookup a type's annotated methods are unreflected with.
     *
     * <p>Only reached for a type that declares an annotated method, so a failure is a real problem: a
     * hierarchy that cannot be accessed cannot serve the method either, and reporting it beats silently
     * dropping the call. Every other type in the hierarchy — including the JDK interfaces every holder
     * tends to implement — is read for annotations without a lookup.</p>
     */
    private MethodHandles.Lookup lookupFor(Class<?> clazz) {
        try {
            return MethodHandles.privateLookupIn(clazz, LOOKUP);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access " + clazz.getName() + " for remote call scanning"
                    + " — its package is not open to this module, so a @RemoteCall method declared there cannot be invoked", e);
        }
    }

    /**
     * Collects every class whose {@code @RemoteCall} methods belong to {@code type}, most specific
     * first: the superclass chain, then the interfaces of every one of those classes (their own
     * super-interfaces included).
     *
     * <p>The two passes are separate on purpose. Java resolves a class method over an interface
     * default, so the interfaces are listed after the whole class chain and therefore lose to it; inside
     * each pass the subtype is listed before the type it refines. Each type appears once, so a diamond
     * of interfaces does not scan — or number — anything twice.</p>
     */
    private void collectHierarchy(Class<?> type, ObjectArrayList<Class<?>> hierarchy) {
        for (var clazz = type; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            if (!hierarchy.contains(clazz)) hierarchy.add(clazz);
        }
        for (var clazz = type; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            collectInterfaces(clazz.getInterfaces(), hierarchy);
        }
    }

    /**
     * Adds the interfaces of one class and, recursively, their super-interfaces — a sub-interface before
     * the interfaces it extends.
     */
    private void collectInterfaces(Class<?>[] interfaces, ObjectArrayList<Class<?>> hierarchy) {
        for (var itf : interfaces) {
            if (hierarchy.contains(itf)) continue;
            hierarchy.add(itf);
            collectInterfaces(itf.getInterfaces(), hierarchy);
        }
    }

    /**
     * Whether {@code candidate} overrides the method behind {@code previous} — same Java name and
     * parameter types, which is how an annotated override (of a superclass method, or of an interface's
     * default method) is allowed to shadow the inherited entry.
     */
    private boolean overrides(RemoteMethod previous, Method candidate) {
        var inherited = previous.method();
        return inherited.getName().equals(candidate.getName())
                && Arrays.equals(inherited.getParameterTypes(), candidate.getParameterTypes());
    }

    @SuppressWarnings("unchecked")
    private RemoteMethod create(String name, Method method, MethodHandles.Lookup lookup, LogicalSide side) {
        if (method.getReturnType() != void.class)
            throw new IllegalStateException("Remote method " + method.getDeclaringClass().getName() + "." + method.getName()
                    + " returns " + method.getReturnType().getSimpleName()
                    + ", but a remote call has no return channel — declare it void and observe the effect through field synchronization");
        var parameterTypes = method.getParameterTypes();
        var genericTypes = method.getGenericParameterTypes();
        // A raw array: a bounded-wildcard element type is not a legal array component (Java allows only
        // an unbounded wildcard there), and every element is filled right below.
        var codecs = new StreamCodec[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            codecs[i] = codecFor(method, i, parameterTypes[i], genericTypes[i]);
        }
        return new RemoteMethod(name, method, invokerHandle(lookup, method), codecs,
                Modifier.isStatic(method.getModifiers()), side);
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
    @SuppressWarnings("unchecked")
    private StreamCodec<? super FriendlyByteBuf, ?> codecFor(Method method, int index, Class<?> type, Type genericType) {
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
        // The cast only spells out what the registry guarantees: whatever buffer the codec was written
        // for, a FriendlyByteBuf is one (a capture variable's lower bound is not enough for javac here).
        return codec.toStreamCodec();
    }
}
