package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncCodec;
import com.gto.datasynclib.FieldDataManager;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.annotations.AdditionalHolder;
import com.gto.datasynclib.annotations.SaveToDisk;
import com.gto.datasynclib.annotations.SyncToClient;
import com.gto.datasynclib.annotations.SyncToServer;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.StringMapData;
import com.gto.datasynclib.listener.ObjNotifiableHolder;
import com.gto.datasynclib.util.ReflectUtil;
import com.gto.datasynclib.util.Registry;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Development-only self-test harness exercising the DataSyncLib persistence and sync
 * machinery through real {@link FieldDataManager} instances.
 *
 * <p>Unlike the network-driven {@link TestBlockEntity}, this harness drives the managers
 * <em>directly</em>: each test builds a fresh holder, mutates fields, serializes to disk
 * ({@code writeToData}/{@code readFromData}) and to the network buffer
 * ({@code writeToNetworkBuffer}/{@code readFromNetworkBuffer}), then deserializes into a new
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
        String name = "";

        @SaveToDisk
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
        MyList<Object> myList;                    // MyList<T> implements List<String> (1 arg: String)
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
                    DataCodec.STRING_CODEC, t -> t.name, MyTag.class);
            registry.unfreeze();
            registry.register("a", new MyTag("a", 1));
            registry.register("b", new MyTag("b", 2));
            registry.freeze(); // must auto-register into global DataSyncCodec

            DataSyncCodec<MyTag> global = DataSyncCodec.get(MyTag.class);
            expect("registry.globalRegistered", global != null, true);
            if (global != null) {
                var data = global.dataWriter.encode(new MyTag("b", 2));
                MyTag decoded = global.dataReader.decode(data, 0);
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
        testNullAndDefaultSkipping();
        testGenericResolution();
        testRegistryGlobalCodec();
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

        Data saved = src.manager.writeToData();

        SyncHolder dst = new SyncHolder();
        dst.manager.readFromData(saved, 0);

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

        byte[] bytes = src.manager.writeToNetworkBuffer(LogicalSide.SERVER, true);

        SyncHolder dst = new SyncHolder();
        dst.manager.readFromNetworkBuffer(LogicalSide.CLIENT, bytes);

        expect("net.counter", src.counter, dst.counter);
        expect("net.name", src.name, dst.name);
        expect("net.facing", src.facing, dst.facing);
        expect("net.child.ticks", src.child.ticks, dst.child.ticks);
        expect("net.child.facing", src.child.facing, dst.child.facing);
    }

    private void testNullAndDefaultSkipping() {
        DefaultHolder src = new DefaultHolder();
        // num stays at default 5, flag stays false, maybe stays null -> none of these are written.
        // other stays 0 but has no @SaveToDisk default -> it IS written.
        Data saved = src.manager.writeToData();
        if (saved instanceof StringMapData map) {
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
        dst.manager.readFromData(saved, 0);
        expect("defaults.roundTrip.num", 5, dst.num);
        expect("defaults.roundTrip.flag", false, dst.flag);
        expect("defaults.roundTrip.maybe", null, dst.maybe);
        expect("defaults.roundTrip.other", 0, dst.other);
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
