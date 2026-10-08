package com.gto.datasynclib.annotations;

import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.remote.RemoteInvoker;
import com.gto.datasynclib.remote.RemoteNetwork;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as remotely invocable through the remote call framework
 * ({@link RemoteInvoker} for the payload, {@link RemoteNetwork} for the default packets).
 *
 * <p>The annotation is the <strong>allow-list</strong> of the framework: only annotated methods
 * can ever be reached by a decoded call, and the caller never names a class — the receiving side
 * resolves the method by name on the packet's target (a block entity or an entity) and on the nested
 * holders reachable from it, so an annotated method of a superclass or of a nested holder is
 * reachable too.</p>
 *
 * <h3>How a method is resolved</h3>
 * <ol>
 *   <li>The <em>sending</em> holder's type hierarchy is scanned once and cached (see
 *       {@link RemoteInvoker#methods(Class)}) — annotated methods declared in superclasses
 *       <em>and in interfaces</em> are included, so a feature interface can declare
 *       {@code @RemoteCall default void onSomethingClient(...)} and every implementor answers it; an
 *       override (of a superclass method or of an interface default) replaces the inherited entry,
 *       following Java's precedence: a class method beats an interface default</li>
 *   <li>The wire name is {@link #value()} when set, otherwise the Java method name; a name must be
 *       unique per class, so give overloads distinct names explicitly. The name is what the caller
 *       writes; on the wire the method travels as its index in that class's sorted name table, preceded
 *       by the target's index in the addressed object's flattened holder tree — two bytes in all, at any
 *       depth</li>
 *   <li>The <em>receiving</em> side resolves that index against the object the packet addressed
 *       ({@code 0} is that object itself, see
 *       {@link com.gto.datasynclib.remote.RemoteRouting}), so a method declared on a nested holder — an
 *       {@link com.gto.datasynclib.annotations.AdditionalHolder @AdditionalHolder} sub-object, or any
 *       field whose type implements {@link com.gto.datasynclib.IFieldDataHolder} — is invoked on that
 *       nested object, even when it declares the same names as the object a packet addressed</li>
 * </ol>
 *
 * <h3>Arguments and return value</h3>
 * <p>Every parameter must have a {@link com.gto.datasynclib.DataSyncCodec} registered for its
 * type (primitives, {@code String}, enums, arrays and the pre-registered Minecraft types all do).
 * Arguments travel as the codec's <strong>network half</strong> ({@code StreamCodec}, written
 * straight into the call's buffer like a synchronized field), so a call costs what its arguments
 * cost and nothing more; {@code null} is carried by a one-byte present/absent marker instead of a
 * {@code NullData} entry.</p>
 *
 * <p>A remote call has <strong>no return channel</strong>, so an annotated method must be
 * {@code void} — a value-returning method is rejected when its class is scanned
 * ({@link RemoteInvoker#methods(Class)}). A call is fire-and-forget: anything the caller has to
 * observe travels back through the field synchronization instead.</p>
 *
 * <h3>Which side may run it</h3>
 * <p>{@link #side()} declares the logical side the method belongs to — {@link LogicalSide#SERVER},
 * {@link LogicalSide#CLIENT} or {@link LogicalSide#BOTH} (the default). A call that arrives on the wrong
 * side is rejected with a clear error before the method runs, so a method that mutates server state
 * cannot be dragged onto a client by a packet, and a client-only reaction cannot be forced onto the
 * server. The side of a received call comes from the object the packet addressed (its level); a target
 * that is not in a level — a unit test, say — has no side, and the check is then skipped.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * class MyBlockEntity extends BlockEntity {
 *     @RemoteCall
 *     public void requestUpgrade(int tier) { ... }              // either side may call it
 *
 *     @RemoteCall(side = LogicalSide.CLIENT)
 *     public void onStructureFormedClient() { ... }             // client-only: the server rejects it
 *
 *     @RemoteCall(value = "configure", side = LogicalSide.SERVER)
 *     private void configure(String key, int value) { ... }      // server-only, renamed on the wire
 * }
 *
 * // server side: encode and send (the packet layer carries it)
 * byte[] payload = FieldDataManager.writeRemoteCall(be, be, "onStructureFormedClient");
 *
 * // the receiving side: decode and invoke on the side it arrived on
 * IFieldDataHolder.handleRemoteCall(be, payload);
 * }</pre>
 *
 * <p><strong>Trust:</strong> a call arrives from another side, so only annotate methods that are
 * safe to run from there — the framework decodes the arguments with the registered codecs, enforces
 * {@link #side()}, and logs (rather than propagates) failures, but it does not check who sent the call.
 * The invoked method owns its side effects, including {@code setChanged()} for a block entity it
 * mutates.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RemoteCall {

    /**
     * The wire name of this method.
     *
     * <p>Defaults to the Java method name. Set it explicitly when the class declares overloads
     * (the wire name must be unique per class), or to decouple the network contract from a
     * refactoring rename.</p>
     *
     * @return the name used in the encoded call, never empty once read by the framework
     */
    String value() default "";

    /**
     * The logical side this method may run on — the direction of the call.
     *
     * <p>{@link LogicalSide#BOTH} (the default) lets either side invoke it; {@link LogicalSide#SERVER}
     * and {@link LogicalSide#CLIENT} restrict it, and a call that arrives on the other side is refused
     * before it is invoked. The check uses the level of the object the packet addressed, so a target
     * outside a level has no side and the call is allowed.</p>
     *
     * @return the side the method belongs to
     */
    LogicalSide side() default LogicalSide.BOTH;
}
