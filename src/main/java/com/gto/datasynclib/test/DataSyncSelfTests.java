package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.FieldDefinitionStorage;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.annotations.*;
import com.gto.datasynclib.datastream.codec.*;
import com.gto.datasynclib.listener.ObjNotifiableHolder;
import com.gto.datasynclib.remote.RemoteBlockEntityPacket;
import com.gto.datasynclib.remote.RemoteEntityPacket;
import com.gto.datasynclib.remote.RemoteInvoker;
import com.gto.datasynclib.util.ByteBufUtil;
import com.gto.datasynclib.util.ReflectUtil;
import com.gto.datasynclib.util.Registry;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Development-only self-test harness exercising the DataSyncLib persistence and sync
 * machinery through real {@link FieldDataManager} instances.
 *
 * <p>Unlike the network-driven {@link TestBlockEntity}, this harness drives the managers
 * <em>directly</em>: each test builds a fresh holder, mutates fields, serializes to disk
 * ({@code writeToValue}/{@code readFromValue}) and to the network buffer
 * ({@code writeToNetworkBuffer}/{@code readFromNetworkBuffer}, through a buffer), then deserializes into a new
 * holder and compares field-by-field. It covers both long-standing features (primitives,
 * defaults, enum/array/collection, conversion, sync) and the newer
 * {@code @AdditionalHolder(childManager = true)} sub-manager feature.</p>
 *
 * <p>Intended to run in a development client/server where Minecraft registries are available
 * (the {@code @SyncToClient} fields and item/fluid codecs require a real registry context).
 * Invoked via {@link #runAll()}.</p>
 */
public final class DataSyncSelfTests {

    private static final String LOG = "[DataSyncSelfTests] ";

    private final List<String> failures = new ArrayList<>();

    /**
     * A plain, non-holder POJO holding several annotated fields.
     */
    static class FlatSub {
        @SaveToDisk
        @SyncToClient
        int a;

        @SaveToDisk
        String s = "";
    }

    /**
     * A non-holder sub-object managed by a child manager via {@code @AdditionalHolder(childManager = true)}.
     */
    static class ChildSub {
        @SaveToDisk
        @SyncToClient
        int ticks;

        @SaveToDisk
        boolean enabled = true;

        @SaveToDisk
        @SyncToClient
        Direction facing = Direction.NORTH;

        @SaveToDisk
        List<Integer> values = new ArrayList<>();
    }

    /**
     * The main test holder. Combines classic {@code @SaveToDisk/@SyncTo*} coverage with a
     * flattened {@code @AdditionalHolder} (old feature) and a {@code childManager = true}
     * one (new feature).
     */
    static class SyncHolder implements IFieldDataHolder {

        private final FieldDataManager manager = new FieldDataManager(this);

        @SaveToDisk
        @SyncToClient
        int counter;

        @SaveToDisk
        long big;

        @SaveToDisk
        double ratio;

        @SaveToDisk
        boolean enabled;

        @SaveToDisk
        @SyncToClient
        String name = "";

        @SaveToDisk
        @SyncToClient
        Direction facing = Direction.WEST;

        @SaveToDisk
        int[] intArray = new int[4];

        @SaveToDisk
        List<String> tags = new ArrayList<>();

        @AdditionalHolder
        FlatSub flat = new FlatSub();

        // New feature: sub-manager (not flattened) for ChildSub, saved AND synced as a unit.
        @SaveToDisk
        @SyncToClient
        @AdditionalHolder(childManager = true)
        ChildSub child = new ChildSub();

        @SyncToServer
        ObjNotifiableHolder<String> message = ObjNotifiableHolder.create(DataSyncCodec.STRING_CODEC);

        @Override
        public FieldDataManager getFieldDataManager() {
            return manager;
        }
    }

    /**
     * A plain object with {@link RemoteCall} methods: an instance method with a primitive
     * parameter, one with a nullable reference parameter, a private one (reached only through the
     * framework), one with primitive parameters of several kinds, and a static one. Remote methods
     * are always {@code void} — a call has no return channel.
     */
    static class RemoteHolder {

        int counter;
        String label = "";
        int scaled;
        boolean negated;
        long big;
        static String staticResult = "";

        @RemoteCall
        void add(int amount) {
            counter += amount;
        }

        @RemoteCall
        void setName(String name) {
            label = name;
        }

        @RemoteCall("reset")
        private void resetCounter() {
            counter = 0;
        }

        @RemoteCall
        void scale(double factor) {
            scaled = (int) (factor * 2);
        }

        @RemoteCall
        void negate(boolean flag) {
            negated = !flag;
        }

        @RemoteCall
        void big(long value) {
            big = value + 1;
        }

        @RemoteCall
        static void describe(String prefix) {
            staticResult = prefix + "-static";
        }
    }

    /**
     * A holder whose only {@link RemoteCall} method returns a value — scanning it must fail,
     * because a remote call has no return channel.
     */
    static class ValueReturningHolder {

        @RemoteCall
        int invalid() {
            return 1;
        }
    }

    /**
     * A plain (non-entity) holder with a remote method, used to check the two sides of the
     * manager-level API: a remote call cannot be <em>sent</em> for it (a packet has no way to address
     * it), but a decoded call can still be <em>handled</em> on it.
     */
    static class RemoteSubHolder implements IFieldDataHolder {

        private final FieldDataManager manager = new FieldDataManager(this);

        int marked;

        @RemoteCall
        void mark(int value) {
            marked = value;
        }

        @Override
        public FieldDataManager getFieldDataManager() {
            return manager;
        }
    }

    /**
     * A downstream extension annotation, the kind a mod registers through
     * {@link FieldDefinitionStorage#registerAnnotation(Class)} to make the library record the
     * fields it marks.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface UnitTag {
        String value() default "";
    }

    /**
     * The same shape with class retention: invisible to reflection at scan time, so registering
     * it must be rejected instead of silently recording nothing.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.FIELD)
    @interface NonRuntimeTag {
    }

    /**
     * A holder for the extension-annotation test: {@code counter} is persisted <em>and</em> tagged,
     * {@code label} carries only the custom annotation and is therefore managed by it alone.
     */
    static class ExtensionHolder implements IFieldDataHolder {

        private final FieldDataManager manager = new FieldDataManager(this);

        @SaveToDisk
        @UnitTag("counted")
        int counter;

        @UnitTag("labelled")
        int label;

        @Override
        public FieldDataManager getFieldDataManager() {
            return manager;
        }
    }

    /**
     * A holder for {@code enabled = false}: the annotations are present for their attributes only.
     *
     * <p>{@code onlyBulk} carries {@code @SaveToDisk(enabled = false)} with a default value and a
     * load listener — it is managed (so the bulk paths carry it) and the attributes still apply, but
     * the field never reaches the incremental save. {@code hiddenToClient}/{@code hiddenToServer}
     * are managed but not part of either sync direction.</p>
     */
    static class DisabledAnnotationHolder implements IFieldDataHolder {

        private final FieldDataManager manager = new FieldDataManager(this);

        @SaveToDisk(enabled = false, defaultValue = "5", listener = "onSaved")
        int onlyBulk;

        @SyncToClient(enabled = false)
        int hiddenToClient;

        @SyncToServer(enabled = false)
        int hiddenToServer;

        int savedCallback = -1;

        @Override
        public FieldDataManager getFieldDataManager() {
            return manager;
        }

        void onSaved(int value) {
            savedCallback = value;
        }
    }

    /**
     * A holder for testing default-value and null skipping.
     */
    static class DefaultHolder implements IFieldDataHolder {

        private final FieldDataManager manager = new FieldDataManager(this);

        @SaveToDisk(defaultValue = "5")
        int num = 5;

        @SaveToDisk(defaultValue = "false")
        boolean flag;

        @SaveToDisk
        @Nullable
        String maybe = null;

        @SaveToDisk
        int other;

        @Override
        public FieldDataManager getFieldDataManager() {
            return manager;
        }
    }

    /**
     * A generic hierarchy: {@code A<T> extends HashMap<String, T>}.
     */
    static class A<T> extends HashMap<String, T> {
    }

    /**
     * A generic class implementing a generic interface ({@code List<String>}): {@code MyList<T> implements List<String>}.
     */
    static class MyList<T> implements List<String> {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public boolean contains(Object o) {
            return false;
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public Object[] toArray() {
            return new Object[0];
        }

        @Override
        public <T1> T1[] toArray(T1[] a) {
            return a;
        }

        @Override
        public boolean add(String s) {
            return false;
        }

        @Override
        public boolean remove(Object o) {
            return false;
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return false;
        }

        @Override
        public boolean addAll(Collection<? extends String> c) {
            return false;
        }

        @Override
        public boolean addAll(int index, Collection<? extends String> c) {
            return false;
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            return false;
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return false;
        }

        @Override
        public void clear() {
        }

        @Override
        public String get(int index) {
            return null;
        }

        @Override
        public String set(int index, String element) {
            return null;
        }

        @Override
        public void add(int index, String element) {
        }

        @Override
        public String remove(int index) {
            return null;
        }

        @Override
        public int indexOf(Object o) {
            return -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            return -1;
        }

        @Override
        public ListIterator<String> listIterator() {
            return Collections.emptyListIterator();
        }

        @Override
        public ListIterator<String> listIterator(int index) {
            return Collections.emptyListIterator();
        }

        @Override
        public List<String> subList(int fromIndex, int toIndex) {
            return Collections.emptyList();
        }
    }

    /**
     * Generic class extending another generic class: {@code Box<U> extends BaseContainer<U, U>}.
     */
    static class BaseContainer<X, Y> {
    }

    static class Box<U> extends BaseContainer<U, U> {
    }

    /**
     * A generic interface used standalone: {@code MyIterable<T> extends Iterable<T>}.
     */
    static class Itr<T> implements Iterable<T> {
        @Override
        public java.util.Iterator<T> iterator() {
            return Collections.emptyIterator();
        }
    }

    /**
     * Holds concrete instantiations so we can read resolved parameterized types via reflection.
     */
    static class GenericRefs {
        A<Integer> ints;                          // A<X> extends HashMap<String, X>  (2 args: String, Integer)
        MyList<String> myList;                    // MyList<T> implements List<String> (1 arg: String)
        Box<String> box;                          // Box<U> extends BaseContainer<U,U> (2 args: String, String)
        Itr<Integer> itr;                         // Itr<T> implements Iterable<T> (1 arg: Integer)
    }

    private void testGenericResolution() {
        try {
            Class<?>[] mapArgs = ReflectUtil.getResolvedGenericArguments(
                    GenericRefs.class.getDeclaredField("ints").getGenericType(), HashMap.class);
            expect("generic.map.count", mapArgs.length, 2);
            expect("generic.map.k", argsName(mapArgs, 0), "String");
            expect("generic.map.v", argsName(mapArgs, 1), "Integer");

            // Non-Map: interface List<String>
            Class<?>[] listArgs = ReflectUtil.getResolvedGenericArguments(
                    GenericRefs.class.getDeclaredField("myList").getGenericType(), List.class);
            expect("generic.list.count", listArgs.length, 1);
            expect("generic.list.e", argsName(listArgs, 0), "String");

            // Generic class BaseContainer<U,U> (2 identical args)
            Class<?>[] boxArgs = ReflectUtil.getResolvedGenericArguments(
                    GenericRefs.class.getDeclaredField("box").getGenericType(), BaseContainer.class);
            expect("generic.box.count", boxArgs.length, 2);
            expect("generic.box.x", argsName(boxArgs, 0), "String");
            expect("generic.box.y", argsName(boxArgs, 1), "String");

            // Custom generic interface Iterable<T>
            Class<?>[] itrArgs = ReflectUtil.getResolvedGenericArguments(
                    GenericRefs.class.getDeclaredField("itr").getGenericType(), Iterable.class);
            expect("generic.itr.count", itrArgs.length, 1);
            expect("generic.itr.t", argsName(itrArgs, 0), "Integer");
        } catch (Throwable t) {
            expect("generic.noThrow", true, false);
            System.err.println(LOG + "generic exception: " + t);
        }
    }

    private static String argsName(Class<?>[] args, int index) {
        return index < args.length && args[index] != null ? args[index].getSimpleName() : "";
    }

    /**
     * A plain value type used to test global codec auto-registration from a {@link Registry}.
     */
    record MyTag(String name, int value) {
    }

    private void testRegistryGlobalCodec() {
        try {
            var registry = new Registry<String, MyTag>("test:tag",
                    ValueCodec.STRING, t -> t.name, MyTag.class);
            registry.unfreeze();
            registry.register("a", new MyTag("a", 1));
            registry.register("b", new MyTag("b", 2));
            registry.freeze(); // must auto-register into global DataSyncCodec

            DataSyncCodec<MyTag> global = DataSyncCodec.get(MyTag.class);
            expect("registry.globalRegistered", global != null, true);
            if (global != null) {
                var value = global.encode(JavaValueOps.INSTANCE, new MyTag("b", 2));
                MyTag decoded = global.decode(JavaValueOps.INSTANCE, value);
                expect("registry.roundTripName", decoded != null ? decoded.name : "", "b");
                expect("registry.roundTripValue", decoded != null ? decoded.value : -1, 2);
            }
        } catch (Throwable t) {
            expect("registry.noThrow", true, false);
            System.err.println(LOG + "registry exception: " + t);
        }
    }

    /**
     * Runs every self-test and reports failures.
     */
    public void run() {
        testDiskRoundTrip();
        testNetworkRoundTrip();
        testSubsetBufferSync();
        testNullAndDefaultSkipping();
        testOptionalCodecs();
        testRemoteCalls();
        testGenericResolution();
        testRegistryGlobalCodec();
        testByteBufUtil();
        testDisabledAnnotations();
        // Last: it registers an annotation, which applies to classes scanned from here on.
        testExtensionAnnotations();
        report();
    }

    private void testDiskRoundTrip() {
        SyncHolder src = new SyncHolder();
        src.counter = 42;
        src.big = 7L << 40;
        src.ratio = 1.25;
        src.enabled = true;
        src.name = "greet";
        src.facing = Direction.SOUTH;
        Arrays.fill(src.intArray, 3);
        src.tags.add("alpha");
        src.tags.add("beta");
        src.flat.a = 11;
        src.flat.s = "flat";
        src.child.ticks = 500;
        src.child.enabled = false;
        src.child.facing = Direction.EAST;
        src.child.values.add(1);
        src.child.values.add(2);

        Object saved = src.manager.writeToValue(JavaValueOps.INSTANCE);

        SyncHolder dst = new SyncHolder();
        dst.manager.readFromValue(saved, JavaValueOps.INSTANCE);

        expect("disk.counter", src.counter, dst.counter);
        expect("disk.big", src.big, dst.big);
        expect("disk.ratio", src.ratio, dst.ratio);
        expect("disk.enabled", src.enabled, dst.enabled);
        expect("disk.name", src.name, dst.name);
        expect("disk.facing", src.facing, dst.facing);
        expect("disk.intArray", src.intArray, dst.intArray);
        expect("disk.tags", src.tags, dst.tags);
        expect("disk.flat.a", src.flat.a, dst.flat.a);
        expect("disk.flat.s", src.flat.s, dst.flat.s);
        expect("disk.child.ticks", src.child.ticks, dst.child.ticks);
        expect("disk.child.enabled", src.child.enabled, dst.child.enabled);
        expect("disk.child.facing", src.child.facing, dst.child.facing);
        expect("disk.child.values", src.child.values, dst.child.values);
    }

    private void testNetworkRoundTrip() {
        SyncHolder src = new SyncHolder();
        src.counter = 99;
        src.name = "net";
        src.facing = Direction.NORTH;
        src.child.ticks = 321;
        src.child.facing = Direction.SOUTH;

        byte[] bytes = syncBytes(src.manager, LogicalSide.SERVER, true);

        SyncHolder dst = new SyncHolder();
        readSyncBytes(dst.manager, LogicalSide.CLIENT, bytes);

        expect("net.counter", src.counter, dst.counter);
        expect("net.name", src.name, dst.name);
        expect("net.facing", src.facing, dst.facing);
        expect("net.child.ticks", src.child.ticks, dst.child.ticks);
        expect("net.child.facing", src.child.facing, dst.child.facing);
    }

    /**
     * Covers the field-subset sync API: writing named fields into a caller-owned buffer (by definition
     * and by name), reading them back through a {@link ByteBufUtil} boundary, the fact that only the
     * listed fields travel and in the listed order, the round trip of an unmanaged name being an error,
     * and the positionality of the payload (the same fields in another order are not the same payload).
     */
    private void testSubsetBufferSync() {
        SyncHolder src = new SyncHolder();
        src.counter = 5;
        src.name = "subset";
        src.facing = Direction.UP;

        // Definition form: one field.
        byte[] single = ByteBufUtil.writeToBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER,
                buf -> src.manager.writeFieldsToBuffer(LogicalSide.SERVER, buf, src.manager.getFieldDefinition("counter")));

        SyncHolder dst = new SyncHolder();
        ByteBufUtil.readFromBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER, single,
                buf -> dst.manager.readFieldsFromBuffer(LogicalSide.CLIENT, buf, dst.manager.getFieldDefinition("counter")));
        expect("subset.singleValue", 5, dst.counter);
        expect("subset.singleLeavesOthers", "", dst.name);
        expect("subset.singleLeavesFacing", Direction.WEST, dst.facing);

        // Name form: several fields, in the order they are listed.
        byte[] multi = ByteBufUtil.writeToBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER,
                buf -> src.manager.writeFieldsToBuffer(LogicalSide.SERVER, buf, "name", "counter", "facing"));

        SyncHolder multiDst = new SyncHolder();
        ByteBufUtil.readFromBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER, multi,
                buf -> multiDst.manager.readFieldsFromBuffer(LogicalSide.CLIENT, buf, "name", "counter", "facing"));
        expect("subset.multiName", "subset", multiDst.name);
        expect("subset.multiCounter", 5, multiDst.counter);
        expect("subset.multiFacing", Direction.UP, multiDst.facing);

        // The payload is positional: the values are read into whatever the receiver lists, so a
        // different order applies wrong (but not loud) values — both sides must name the same fields
        // in the same order. Here "counter" (VarInt 5) and "enabled" (boolean false) arrive, and the
        // receiver asks for them the other way round: the 5 is read as the boolean and the 0 byte as
        // the VarInt, so the target ends up with the values swapped rather than with an error.
        byte[] pair = ByteBufUtil.writeToBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER,
                buf -> src.manager.writeFieldsToBuffer(LogicalSide.SERVER, buf, "counter", "enabled"));

        SyncHolder swappedDst = new SyncHolder();
        ByteBufUtil.readFromBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER, pair,
                buf -> swappedDst.manager.readFieldsFromBuffer(LogicalSide.CLIENT, buf, "enabled", "counter"));
        expect("subset.orderMatters", true, swappedDst.enabled && swappedDst.counter == 0);

        // A name the class does not manage is rejected on both directions.
        boolean writeRejected = false;
        try {
            ByteBufUtil.writeToBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER,
                    buf -> src.manager.writeFieldsToBuffer(LogicalSide.SERVER, buf, "nope"));
        } catch (IllegalArgumentException e) {
            writeRejected = true;
        }
        expect("subset.unknownNameWriteRejected", true, writeRejected);

        boolean readRejected = false;
        try {
            ByteBufUtil.readFromBytes(ByteBufUtil.FRIENDLY_BYTE_BUF_WRAPPER, multi,
                    buf -> multiDst.manager.readFieldsFromBuffer(LogicalSide.CLIENT, buf, "nope"));
        } catch (IllegalArgumentException e) {
            readRejected = true;
        }
        expect("subset.unknownNameReadRejected", true, readRejected);

        // The subset payload is framed and read like any other nested payload: a slice from the parent
        // buffer, no byte[] copy, and the parent's reader index lands right after it.
        var framed = Unpooled.buffer();
        try {
            ByteBufUtil.writePayload(framed, buf -> src.manager.writeFieldsToBuffer(
                    LogicalSide.SERVER, ByteBufUtil.toFriendlyByteBuf(buf), "counter"));
            framed.writeByte(7);
            SyncHolder framedDst = new SyncHolder();
            ByteBufUtil.readPayload(framed, buf -> framedDst.manager.readFieldsFromBuffer(
                    LogicalSide.CLIENT, ByteBufUtil.toFriendlyByteBuf(buf), "counter"));
            expect("subset.framedValue", 5, framedDst.counter);
            expect("subset.framedTail", 7, (int) framed.readByte());
        } finally {
            framed.release();
        }
    }

    private void testNullAndDefaultSkipping() {
        DefaultHolder src = new DefaultHolder();
        // num stays at default 5, flag stays false, maybe stays null -> none of these are written.
        // other stays 0 but has no @SaveToDisk default -> it IS written.
        Object saved = src.manager.writeToValue(JavaValueOps.INSTANCE);
        if (saved instanceof Map<?, ?> map) {
            expect("defaults.numSkipped", !map.containsKey("num"), true);
            expect("defaults.flagSkipped", !map.containsKey("flag"), true);
            expect("defaults.nullSkipped", !map.containsKey("maybe"), true);
            expect("defaults.otherWritten", map.containsKey("other"), true);
        } else {
            // Empty map: only possible if even "other" was skipped, which would be wrong.
            expect("defaults.mapNonEmpty", false, true);
        }

        // Round-trip keeps defaults intact.
        DefaultHolder dst = new DefaultHolder();
        dst.manager.readFromValue(saved, JavaValueOps.INSTANCE);
        expect("defaults.roundTrip.num", 5, dst.num);
        expect("defaults.roundTrip.flag", false, dst.flag);
        expect("defaults.roundTrip.maybe", null, dst.maybe);
        expect("defaults.roundTrip.other", 0, dst.other);
    }

    /**
     * Covers the remote call module: scanning ({@code @RemoteCall} on instance, private and static
     * methods), the name + arguments payload round trip through {@link RemoteInvoker}, primitive
     * arguments of several kinds, the rejection of an unknown name and of a value-returning method,
     * and the wire format of both default packets.
     */
    private void testRemoteCalls() {
        var holder = new RemoteHolder();

        // Instance method with a primitive argument.
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "add", 5));
        expect("remote.add", 5, holder.counter);

        // Primitive arguments of the other kinds, boxed in and unboxed by the invocation handle.
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "scale", 1.25));
        expect("remote.scale", 2, holder.scaled);
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "negate", false));
        expect("remote.negate", true, holder.negated);
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "big", (1L << 40) - 1));
        expect("remote.big", 1L << 40, holder.big);

        // Reference argument, then an explicit null argument.
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "setName", "x"));
        expect("remote.setName", "x", holder.label);
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "setName", (Object) null));
        expect("remote.setNameNull", null, holder.label);

        // A private method is reachable only through the framework.
        RemoteInvoker.handle(holder, RemoteInvoker.write(holder, "reset"));
        expect("remote.privateReset", 0, holder.counter);

        // A static method needs no instance: instance-free write and handle.
        RemoteInvoker.handle(RemoteHolder.class, RemoteInvoker.write(RemoteHolder.class, "describe", "p"));
        expect("remote.static", "p-static", RemoteHolder.staticResult);

        // The scanned table is cached and introspectable.
        expect("remote.scanned", true,
                RemoteInvoker.has(RemoteHolder.class, "add")
                        && RemoteInvoker.has(RemoteHolder.class, "reset")
                        && !RemoteInvoker.has(RemoteHolder.class, "missing"));
        expect("remote.parameterCount", 1, RemoteInvoker.method(RemoteHolder.class, "describe").parameterCount());

        // An unknown name is rejected instead of silently encoding nothing.
        boolean rejectedUnknown = false;
        try {
            RemoteInvoker.write(holder, "missing");
        } catch (IllegalArgumentException e) {
            rejectedUnknown = true;
        }
        expect("remote.unknownRejected", true, rejectedUnknown);

        // A value-returning method is rejected at scan time: a call has no return channel.
        boolean rejectedValueReturn = false;
        try {
            RemoteInvoker.methods(ValueReturningHolder.class);
        } catch (IllegalStateException e) {
            rejectedValueReturn = true;
        }
        expect("remote.valueReturnRejected", true, rejectedValueReturn);

        // The manager caches this holder's remote table lazily and validates names locally.
        var subHolder = new RemoteSubHolder();
        var subManager = subHolder.getFieldDataManager();
        expect("remote.managerTableCached", true, subManager.getRemoteMethods() == subManager.getRemoteMethods());
        expect("remote.managerMethodFound", true, subManager.getRemoteMethod("mark") != null);
        expect("remote.managerUnknownNull", null, subManager.getRemoteMethod("missing"));
        boolean rejectedUnknownOnManager = false;
        try {
            subHolder.writeRemoteCall(subHolder, "missing");
        } catch (IllegalArgumentException e) {
            rejectedUnknownOnManager = true;
        }
        expect("remote.managerUnknownRejected", true, rejectedUnknownOnManager);

        // The holder produces the payload; the packet layer applies it to the object it addressed.
        byte[] remotePayload = subHolder.writeRemoteCall(subHolder, "mark", 3);
        expect("remote.payloadNotEmpty", true, remotePayload.length > 0);
        IFieldDataHolder.handleRemoteCall(subHolder, remotePayload);
        expect("remote.managerRoundTrip", 3, subHolder.marked);

        // Both default packets carry target + payload over the wire.
        byte[] payload = RemoteInvoker.write(holder, "add", 3);
        var blockEntityPacket = new RemoteBlockEntityPacket(new BlockPos(1, 2, 3), payload);
        var blockEntityBuf = Unpooled.buffer();
        try {
            blockEntityPacket.encode(new FriendlyByteBuf(blockEntityBuf));
            var decoded = RemoteBlockEntityPacket.decode(new FriendlyByteBuf(blockEntityBuf));
            expect("remote.packet.blockEntity.pos", blockEntityPacket.pos(), decoded.pos());
            expect("remote.packet.blockEntity.data", true, Arrays.equals(payload, decoded.data()));
            RemoteInvoker.handle(holder, decoded.data());
            expect("remote.packet.blockEntity.invoked", 3, holder.counter);
        } finally {
            blockEntityBuf.release();
        }

        var entityPacket = new RemoteEntityPacket(42, payload);
        var entityBuf = Unpooled.buffer();
        try {
            entityPacket.encode(new FriendlyByteBuf(entityBuf));
            var decoded = RemoteEntityPacket.decode(new FriendlyByteBuf(entityBuf));
            expect("remote.packet.entity.id", 42, decoded.entityId());
            expect("remote.packet.entity.data", true, Arrays.equals(payload, decoded.data()));
        } finally {
            entityBuf.release();
        }
    }

    /**
     * Writes one value with a stream codec and returns the raw bytes, so a test can also assert on
     * the size of the encoding (the optional builders must spend exactly one byte on "absent").
     */
    private static <T> byte[] streamEncode(StreamCodec<? super FriendlyByteBuf, T> codec, T value) {
        var buf = Unpooled.buffer();
        try {
            codec.encode(new FriendlyByteBuf(buf), value);
            var bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static <T> T streamDecode(StreamCodec<? super FriendlyByteBuf, T> codec, byte[] bytes) {
        var buf = Unpooled.wrappedBuffer(bytes);
        try {
            return codec.decode(new FriendlyByteBuf(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * Covers the {@code optional} builders (instance and static; plain, default value, default
     * supplier) on both paths, plus the {@code asKey} / {@code asValue} map adapters: a {@code null}
     * value — and, with a default, one equal to the default — must be stored as the absent marker
     * (the carrier''s null value on disk, a {@code false} boolean on the wire, one byte on either
     * path) and come back as {@code null} / the default, while every other value round-trips
     * unchanged.
     */
    private void testOptionalCodecs() {
        var ops = JavaValueOps.INSTANCE;
        // ---- optional(): null is the null value, anything else is the payload itself ----
        var nativeOptional = ValueCodec.STRING.optional();
        expect("optional.native.nullMarker", true, ops.isNull(nativeOptional.encode(ops, null)));
        expect("optional.native.value", "x", nativeOptional.decode(ops, nativeOptional.encode(ops, "x")));
        expect("optional.native.decodeNull", null, nativeOptional.decode(ops, ops.createNull()));

        // ---- optional(): one boolean marker, then the payload ----
        var streamOptional = ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8);
        byte[] absentBytes = streamEncode(streamOptional, null);
        expect("optional.stream.nullMarkerSize", absentBytes.length, 1);
        expect("optional.stream.nullMarkerFalse", absentBytes[0], (byte) 0);
        byte[] presentBytes = streamEncode(streamOptional, "x");
        expect("optional.stream.valueMarkerTrue", presentBytes[0], (byte) 1);
        String streamNull = streamDecode(streamOptional, absentBytes);
        expect("optional.stream.decodeNull", null, streamNull);
        expect("optional.stream.decodeValue", "x", streamDecode(streamOptional, presentBytes));

        // ---- optional(defaultValue): the default is stored as absent, exactly like null ----
        var nativeDefault = ValueCodec.STRING.optional("d");
        expect("optional.default.native.nullMarker", true, ops.isNull(nativeDefault.encode(ops, null)));
        expect("optional.default.native.defaultMarker", true, ops.isNull(nativeDefault.encode(ops, "d")));
        expect("optional.default.native.value", "x", nativeDefault.decode(ops, nativeDefault.encode(ops, "x")));
        expect("optional.default.native.decodeDefault", "d", nativeDefault.decode(ops, ops.createNull()));

        var streamDefault = ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8, "d");
        expect("optional.default.stream.defaultMarkerSize", streamEncode(streamDefault, "d").length, 1);
        expect("optional.default.stream.decodeDefault", "d", streamDecode(streamDefault, streamEncode(streamDefault, "d")));
        expect("optional.default.stream.decodeValue", "x", streamDecode(streamDefault, streamEncode(streamDefault, "x")));
        // The static builder form takes the codec and the default side by side.


        // ---- optional(Supplier): a fresh mutable default per decode ----
        // A bare lambda or method reference is ambiguous between the value and the supplier
        // overload, so the supplier is spelled out here — the caveat documented on the builders.
        Supplier<List<String>> newList = ArrayList::new;
        ValueCodec<List<String>> listCodec = ValueCodec.collection(ArrayList::new, ValueCodec.STRING);
        var nativeListDefault = listCodec.optional(newList);
        expect("optional.supplier.native.emptyMarker", true, ops.isNull(nativeListDefault.encode(ops, new ArrayList<>())));
        expect("optional.supplier.native.decode", List.of("a"), nativeListDefault.decode(ops, nativeListDefault.encode(ops, List.of("a"))));
        List<String> firstDefault = nativeListDefault.decode(ops, ops.createNull());
        List<String> secondDefault = nativeListDefault.decode(ops, ops.createNull());
        expect("optional.supplier.native.default", true, firstDefault.isEmpty() && secondDefault.isEmpty());
        expect("optional.supplier.native.freshInstance", true, firstDefault != secondDefault);

        StreamCodec<FriendlyByteBuf, List<String>> streamListCodec =
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8);
        var streamListDefault = ByteBufCodecs.optional(streamListCodec, newList);
        expect("optional.supplier.stream.emptyMarkerSize", streamEncode(streamListDefault, new ArrayList<>()).length, 1);
        expect("optional.supplier.stream.decode", List.of("a"),
                streamDecode(streamListDefault, streamEncode(streamListDefault, new ArrayList<>(List.of("a")))));
        expect("optional.supplier.stream.default", new ArrayList<>(),
                streamDecode(streamListDefault, streamEncode(streamListDefault, new ArrayList<>())));


        // ---- CombinedCodec: one codec, both paths ----
        var combinedOptional = DataSyncCodec.STRING_CODEC.optional();
        expect("optional.combined.disk.nullMarker", true, ops.isNull(combinedOptional.encode(ops, null)));
        expect("optional.combined.disk.decodeNull", null, combinedOptional.decode(ops, ops.createNull()));
        expect("optional.combined.disk.value", "v", combinedOptional.decode(ops, combinedOptional.encode(ops, "v")));
        String combinedStreamNull = streamDecode(combinedOptional, streamEncode(combinedOptional, null));
        expect("optional.combined.stream.decodeNull", null, combinedStreamNull);
        expect("optional.combined.stream.value", "v", streamDecode(combinedOptional, streamEncode(combinedOptional, "v")));

        var combinedDefault = DataSyncCodec.STRING_CODEC.optional("d");
        expect("optional.combined.default.disk.defaultMarker", true, ops.isNull(combinedDefault.encode(ops, "d")));
        expect("optional.combined.default.disk.decodeDefault", "d", combinedDefault.decode(ops, ops.createNull()));
        expect("optional.combined.default.disk.decodeValue", "x", combinedDefault.decode(ops, combinedDefault.encode(ops, "x")));
        expect("optional.combined.default.stream.defaultMarkerSize", streamEncode(combinedDefault, "d").length, 1);
        expect("optional.combined.default.stream.decodeDefault", "d",
                streamDecode(combinedDefault, streamEncode(combinedDefault, "d")));
        expect("optional.combined.default.stream.decodeValue", "x",
                streamDecode(combinedDefault, streamEncode(combinedDefault, "x")));

        DataSyncCodec<List<String>> combinedListCodec = CombinedCodec.collection(ArrayList::new, DataSyncCodec.STRING_CODEC);
        var combinedListDefault = combinedListCodec.optional(newList);
        expect("optional.combined.supplier.diskMarker", true, ops.isNull(combinedListDefault.encode(ops, new ArrayList<>())));
        expect("optional.combined.supplier.diskValue", List.of("a"),
                combinedListDefault.decode(ops, combinedListDefault.encode(ops, List.of("a"))));
        expect("optional.combined.supplier.wireAbsent", List.of(),
                streamDecode(combinedListDefault, streamEncode(combinedListDefault, new ArrayList<>())));

        // ---- asKey / asValue: this codec supplies one side of the map ----
        // The map type stays the caller's choice, exactly as on the static map builder.
        IntFunction<Map<String, Integer>> stringKeyed = HashMap::new;
        IntFunction<Map<Integer, String>> intKeyed = HashMap::new;

        ValueCodec<Map<String, Integer>> keyedByString = ValueCodec.map(stringKeyed, ValueCodec.STRING, ValueCodec.INT);
        expect("map.asKey.native", Map.of("a", 1), keyedByString.decode(ops, keyedByString.encode(ops, Map.of("a", 1))));
        ValueCodec<Map<Integer, String>> valuedByString = ValueCodec.map(intKeyed, ValueCodec.INT, ValueCodec.STRING);
        expect("map.asValue.native", Map.of(1, "a"), valuedByString.decode(ops, valuedByString.encode(ops, Map.of(1, "a"))));

        var streamKeyedByString = ByteBufCodecs.map(stringKeyed, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT);
        expect("map.asKey.stream", Map.of("a", 1),
                streamDecode(streamKeyedByString, streamEncode(streamKeyedByString, Map.of("a", 1))));
        var streamValuedByString = ByteBufCodecs.map(intKeyed, ByteBufCodecs.VAR_INT, ByteBufCodecs.STRING_UTF8);
        expect("map.asValue.stream", Map.of(1, "a"),
                streamDecode(streamValuedByString, streamEncode(streamValuedByString, Map.of(1, "a"))));

        var combinedKeyedByString = DataSyncCodec.STRING_CODEC.asKey(stringKeyed, DataSyncCodec.INT_CODEC);
        expect("map.asKey.combined.disk", Map.of("a", 1),
                combinedKeyedByString.decode(ops, combinedKeyedByString.encode(ops, Map.of("a", 1))));
        expect("map.asKey.combined.wire", Map.of("a", 1),
                streamDecode(combinedKeyedByString, streamEncode(combinedKeyedByString, Map.of("a", 1))));
        var combinedValuedByString = DataSyncCodec.STRING_CODEC.asValue(intKeyed, DataSyncCodec.INT_CODEC);
        expect("map.asValue.combined.disk", Map.of(1, "a"),
                combinedValuedByString.decode(ops, combinedValuedByString.encode(ops, Map.of(1, "a"))));
        expect("map.asValue.combined.wire", Map.of(1, "a"),
                streamDecode(combinedValuedByString, streamEncode(combinedValuedByString, Map.of(1, "a"))));

        // ---- the remaining instance mirrors of the static builders ----
        IntFunction<List<String>> listFactory = ArrayList::new;
        var nativeCollection = ValueCodec.collection(listFactory, ValueCodec.STRING);
        expect("mirror.collection.native", List.of("a", "b"),
                nativeCollection.decode(ops, nativeCollection.encode(ops, new ArrayList<>(List.of("a", "b")))));
        var streamCollection = ByteBufCodecs.collection(listFactory, ByteBufCodecs.STRING_UTF8);
        expect("mirror.collection.stream", List.of("a", "b"),
                streamDecode(streamCollection, streamEncode(streamCollection, new ArrayList<>(List.of("a", "b")))));

        var nativeArray = ValueCodec.array(String.class, ValueCodec.STRING);
        expect("mirror.array.native", true,
                Arrays.equals(new String[]{"a", "b"}, nativeArray.decode(ops, nativeArray.encode(ops, new String[]{"a", "b"}))));
        var streamArray = ByteBufCodecs.array(String.class, ByteBufCodecs.STRING_UTF8);
        expect("mirror.array.stream", true,
                Arrays.equals(new String[]{"a", "b"}, streamDecode(streamArray, streamEncode(streamArray, new String[]{"a", "b"}))));

        var nativeConverted = ValueCodec.INT.convert(Integer::parseInt, Object::toString);
        expect("mirror.convert.native", "7", nativeConverted.decode(ops, nativeConverted.encode(ops, "7")));
        var streamConverted = ByteBufCodecs.VAR_INT.convert(Integer::parseInt, Object::toString);
        expect("mirror.convert.stream", "7", streamDecode(streamConverted, streamEncode(streamConverted, "7")));

        var covered = DataSyncCodec.STRING_CODEC.collection(listFactory);
        expect("mirror.collection.combined.disk", List.of("a"),
                covered.decode(ops, covered.encode(ops, new ArrayList<>(List.of("a")))));
        expect("mirror.collection.combined.wire", List.of("a"),
                streamDecode(covered, streamEncode(covered, new ArrayList<>(List.of("a")))));
        var coveredList = DataSyncCodec.STRING_CODEC.list();
        expect("mirror.list.combined", List.of("a"), coveredList.decode(ops, coveredList.encode(ops, List.of("a"))));
        var coveredSet = DataSyncCodec.STRING_CODEC.set();
        expect("mirror.set.combined", Set.of("a"), coveredSet.decode(ops, coveredSet.encode(ops, Set.of("a"))));
        var coveredArray = DataSyncCodec.STRING_CODEC.array(String.class);
        expect("mirror.array.combined.disk", true,
                Arrays.equals(new String[]{"a"}, coveredArray.decode(ops, coveredArray.encode(ops, new String[]{"a"}))));
        var coveredConverted = DataSyncCodec.INT_CODEC.convert(Integer::parseInt, Object::toString);
        expect("mirror.convert.combined.disk", "7", coveredConverted.decode(ops, coveredConverted.encode(ops, "7")));

        // Cross-path mirrors: adapt a half to the other path, or to a combined codec.
        var valueAsStream = ByteBufCodecs.fromValueCodec(0, ValueCodec.STRING);
        expect("mirror.valueAsStream", "x", streamDecode(valueAsStream, streamEncode(valueAsStream, "x")));
        var fromValue = DataSyncCodec.of(0, ValueCodec.STRING);
        expect("mirror.ofValueCodec.disk", "x", fromValue.decode(ops, fromValue.encode(ops, "x")));
        expect("mirror.ofValueCodec.wire", "x", streamDecode(fromValue, streamEncode(fromValue, "x")));
        var fromStream = DataSyncCodec.of(ByteBufCodecs.STRING_UTF8);
        expect("mirror.ofStreamCodec.disk", "x", fromStream.decode(ops, fromStream.encode(ops, "x")));
        expect("mirror.ofStreamCodec.wire", "x", streamDecode(fromStream, streamEncode(fromStream, "x")));
        expect("mirror.toDataSyncCodec.self", true, DataSyncCodec.STRING_CODEC.toDataSyncCodec() == DataSyncCodec.STRING_CODEC);
    }

    /**
     * Covers the extension-annotation registry: a downstream annotation registered through
     * {@link FieldDefinitionStorage#registerAnnotation(Class)} is collected while the class is
     * scanned, keyed by annotation type and paired with the field's definition
     * ({@link FieldDefinitionStorage.AnnotatedField}); a field carrying only the custom annotation
     * is managed like an {@code @AddToManager} field — it has a definition and round-trips through
     * the bulk value path, but is not part of the incremental save — and a registration that could
     * never be seen by a scan is rejected.
     */
    private void testExtensionAnnotations() {
        try {
            // Must happen before ExtensionHolder is first scanned (i.e. before it is constructed).
            // The factory is the optional user metadata: it runs once per annotated field and its
            // result is stored in AnnotatedField#metadata().
            FieldDefinitionStorage.registerAnnotation(UnitTag.class,
                    (lookup, definition, type, annotation) -> annotation.value().toUpperCase(Locale.ROOT));
            var storage = FieldDefinitionStorage.get(ExtensionHolder.class);

            expect("extensions.hasAnnotated", true, storage.hasAnnotatedFields(UnitTag.class));
            expect("extensions.annotationTypes", true, storage.getAnnotationTypes().contains(UnitTag.class));
            expect("extensions.unregisteredEmpty", 0,
                    storage.getAnnotatedFields(Retention.class).length);

            var counterField = ExtensionHolder.class.getDeclaredField("counter");
            var labelField = ExtensionHolder.class.getDeclaredField("label");
            var tagged = storage.getAnnotatedFields(UnitTag.class);
            expect("extensions.count", 2, tagged.length);

            boolean seenCounter = false;
            boolean seenLabel = false;
            for (var annotated : tagged) {
                if (annotated.field().equals(counterField)) {
                    seenCounter = "counted".equals(annotated.annotation().value());
                    expect("extensions.counterDefinition", true,
                            annotated.definition() == storage.getFieldDefinition(counterField));
                    expect("extensions.counterMetadata", "COUNTED", annotated.metadata());
                } else if (annotated.field().equals(labelField)) {
                    seenLabel = "labelled".equals(annotated.annotation().value());
                    expect("extensions.labelDefinition", true,
                            annotated.definition() == storage.getFieldDefinition(labelField));
                    expect("extensions.labelMetadata", "LABELLED", annotated.metadata());
                } else {
                    expect("extensions.unexpectedField", true, false);
                }
            }
            expect("extensions.bothTagged", true, seenCounter && seenLabel);

            // The custom annotation alone manages the field: a definition exists, but it is not a
            // saved field (only @SaveToDisk puts a field on the incremental path).
            var labelDefinition = storage.getFieldDefinition(labelField);
            expect("extensions.labelManaged", true, labelDefinition != null);
            expect("extensions.labelNotSaved", false, labelDefinition != null && labelDefinition.isSave);

            ExtensionHolder src = new ExtensionHolder();
            src.counter = 5;
            src.label = 7;
            expect("extensions.readThroughDefinition", (Object) 7, labelDefinition == null ? null : labelDefinition.get(src));

            ExtensionHolder dst = new ExtensionHolder();
            var bulk = src.getFieldDataManager().writeAllToValue(JavaValueOps.INSTANCE);
            dst.getFieldDataManager().readAllFromValue(bulk, JavaValueOps.INSTANCE);
            expect("extensions.bulkRoundTrip.counter", 5, dst.counter);
            expect("extensions.bulkRoundTrip.label", 7, dst.label);

            var saved = src.getFieldDataManager().writeToValue(JavaValueOps.INSTANCE);
            expect("extensions.notSavedIncrementally", false,
                    saved instanceof Map<?, ?> map && map.containsKey("label"));

            boolean rejectedNonRuntime = false;
            try {
                FieldDefinitionStorage.registerAnnotation(NonRuntimeTag.class);
            } catch (IllegalArgumentException e) {
                rejectedNonRuntime = true;
            }
            expect("extensions.rejectsNonRuntime", true, rejectedNonRuntime);
        } catch (Throwable t) {
            expect("extensions.noThrow", true, false);
            System.err.println(LOG + "extensions exception: " + t);
        }
    }

    /**
    /**
     * Covers {@link ByteBufUtil}: the {@code byte[]} boundaries and the framed payloads.
     *
     * <p>A payload written with {@link ByteBufUtil#writeToBytes} comes back through
     * {@link ByteBufUtil#readFromBytes} without a copy (the array is wrapped), an empty array skips the
     * consumer; a nested payload written with {@link ByteBufUtil#writePayload} (a {@code short} prefix) or
     * {@link ByteBufUtil#writeBigPayload} (an {@code int} prefix) is read back as a slice of the outer
     * buffer, the outer reader index lands right after it, an empty payload skips the consumer, and a
     * prefix that claims more bytes than are readable is rejected.</p>
     */
    private void testByteBufUtil() {
        // the byte[] boundary
        var bytes = ByteBufUtil.writeToBytes(FriendlyByteBuf::new, buf -> buf.writeVarInt(42));
        expect("byteBuf.bytesLength", 1, bytes.length);
        var written = new int[1];
        ByteBufUtil.readFromBytes(FriendlyByteBuf::new, bytes, buf -> written[0] = buf.readVarInt());
        expect("byteBuf.bytesRoundTrip", 42, written[0]);
        var called = new boolean[1];
        ByteBufUtil.readFromBytes(FriendlyByteBuf::new, new byte[0], buf -> called[0] = true);
        expect("byteBuf.emptyArraySkipsConsumer", false, called[0]);
        expect("byteBuf.getBytesEmpty", 0, ByteBufUtil.getBytes(Unpooled.buffer()).length);

        // a plain buffer gets a FriendlyByteBuf view over the very same bytes, an existing view is kept
        var underlying = Unpooled.buffer();
        try {
            ByteBufUtil.toFriendlyByteBuf(underlying).writeVarInt(7);
            expect("byteBuf.viewSharesBuffer", 7, (int) underlying.readByte());
        } finally {
            underlying.release();
        }
        var friendly = new FriendlyByteBuf(Unpooled.buffer());
        try {
            expect("byteBuf.viewIsItself", true, ByteBufUtil.toFriendlyByteBuf(friendly) == friendly);
        } finally {
            friendly.release();
        }

        // a short-framed payload: the consumer reads a slice, and the buffer continues after it
        var framed = Unpooled.buffer();
        try {
            ByteBufUtil.writePayload(framed, buf -> buf.writeInt(11));
            expect("byteBuf.payloadWireLength", 6, framed.readableBytes());
            framed.writeByte(5);
            ByteBufUtil.readPayload(framed, buf -> expect("byteBuf.payloadValue", 11, buf.readInt()));
            expect("byteBuf.payloadFollowedBy", 5, (int) framed.readByte());
        } finally {
            framed.release();
        }

        // an int prefix for a payload that may exceed the short one
        var big = Unpooled.buffer();
        try {
            ByteBufUtil.writeBigPayload(big, buf -> buf.writeLong(1L << 40));
            expect("byteBuf.bigPayloadWireLength", 12, big.readableBytes());
            ByteBufUtil.readBigPayload(big, buf -> expect("byteBuf.bigPayloadValue", 1L << 40, buf.readLong()));
            expect("byteBuf.bigPayloadConsumed", 0, big.readableBytes());
        } finally {
            big.release();
        }

        // an empty payload skips the consumer but is still consumed
        var empty = Unpooled.buffer();
        try {
            ByteBufUtil.writePayload(empty, buf -> {
            });
            var skipped = new boolean[1];
            ByteBufUtil.readPayload(empty, buf -> skipped[0] = true);
            expect("byteBuf.emptyPayloadSkipped", false, skipped[0]);
            expect("byteBuf.emptyPayloadConsumed", 0, empty.readableBytes());
        } finally {
            empty.release();
        }

        // a prefix claiming more bytes than are readable is rejected
        var bad = Unpooled.buffer();
        try {
            bad.writeInt(8);
            bad.writeInt(1);
            boolean rejected = false;
            try {
                ByteBufUtil.readBigPayload(bad, buf -> {
                });
            } catch (IndexOutOfBoundsException e) {
                rejected = true;
            }
            expect("byteBuf.badLengthRejected", true, rejected);
        } finally {
            bad.release();
        }
    }

    /**
     * Covers {@code enabled = false} on {@code @SaveToDisk}, {@code @SyncToClient} and
     * {@code @SyncToServer}: the annotation keeps the field managed and keeps its attributes — key,
     * default value, listener, skip predicate — but its own path is switched off, so the field is
     * neither saved incrementally nor synchronized in either direction. The bulk paths still carry
     * it, a read through them fires the disabled annotation's listener, and its default-value filter
     * still applies to the write.
     */
    private void testDisabledAnnotations() {
        DisabledAnnotationHolder holder = new DisabledAnnotationHolder();
        var ops = JavaValueOps.INSTANCE;
        holder.onlyBulk = 6;
        holder.hiddenToClient = 1;
        holder.hiddenToServer = 2;

        // the incremental save path knows nothing about the field...
        var manager = holder.getFieldDataManager();
        expect("disabled.saveFields", false, manager.hasSaveFields());
        expect("disabled.savePayloadEmpty", true, ops.isNull(manager.writeToValue(ops)));

        // ...while the bulk path carries it, and reading it back fires the listener
        var all = manager.writeAllToValue(ops);
        expect("disabled.bulkCarried", true, ops.getStringMap(all).containsKey("onlyBulk"));
        var dst = new DisabledAnnotationHolder();
        dst.getFieldDataManager().readAllFromValue(all, ops);
        expect("disabled.bulkRead", 6, dst.onlyBulk);
        expect("disabled.listenerFired", 6, dst.savedCallback);

        // the default-value filter of the disabled annotation still applies to that write
        holder.onlyBulk = 5;
        expect("disabled.defaultSkipped", false,
                ops.getStringMap(manager.writeAllToValue(ops)).containsKey("onlyBulk"));

        // neither sync direction knows the field
        expect("disabled.syncToClient", false, manager.hasSyncFields(LogicalSide.SERVER));
        expect("disabled.syncToServer", false, manager.hasSyncFields(LogicalSide.CLIENT));
        expect("disabled.clientBufferEmpty", 0,
                syncBytes(manager, LogicalSide.SERVER, true).length);
        expect("disabled.serverBufferEmpty", 0,
                syncBytes(manager, LogicalSide.CLIENT, true).length);
    }

    /**
     * Serializes a holder's sync payload into a {@code byte[]} through
     * {@link ByteBufUtil#writeToBytes(java.util.function.Function, java.util.function.Consumer)} — the shape a
     * packet carries.
     *
     * @param manager  the holder's manager
     * @param side     the direction to serialize
     * @param writeAll {@code true} for a full write, {@code false} for an incremental one
     * @return the payload
     */
    private static byte[] syncBytes(FieldDataManager manager, LogicalSide side, boolean writeAll) {
        return ByteBufUtil.writeToBytes(FriendlyByteBuf::new, buf -> manager.writeToNetworkBuffer(side, buf, writeAll));
    }

    /**
     * Applies a payload produced by {@link #syncBytes(FieldDataManager, LogicalSide, boolean)}; the array is
     * wrapped, not copied.
     *
     * @param manager the receiving holder's manager
     * @param side    the direction being read
     * @param data    the payload
     */
    private static void readSyncBytes(FieldDataManager manager, LogicalSide side, byte[] data) {
        ByteBufUtil.readFromBytes(FriendlyByteBuf::new, data, buf -> manager.readFromNetworkBuffer(side, buf));
    }

    private <T> void expect(String name, T expected, T actual) {
        if (Objects.equals(expected, actual)) {
            System.out.println(LOG + "PASS " + name);
        } else {
            failures.add(name + " expected=" + expected + " actual=" + actual);
            System.err.println(LOG + "FAIL " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    private void expect(String name, int[] expected, int[] actual) {
        expect(name, Arrays.equals(expected, actual));
    }

    private void expect(String name, boolean ok) {
        if (ok) {
            System.out.println(LOG + "PASS " + name);
        } else {
            failures.add(name);
            System.err.println(LOG + "FAIL " + name);
        }
    }

    private void report() {
        if (failures.isEmpty()) {
            System.out.println(LOG + "ALL TESTS PASSED");
        } else {
            System.err.println(LOG + failures.size() + " FAILURE(S): " + failures);
        }
    }

    /**
     * Convenience: run from anywhere.
     */
    public static void runAll() {
        new DataSyncSelfTests().run();
    }
}