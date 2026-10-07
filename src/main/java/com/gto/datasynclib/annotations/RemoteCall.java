package com.gto.datasynclib.annotations;

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
 *   <li>The <em>sending</em> holder's class hierarchy is scanned once and cached (see
 *       {@link RemoteInvoker#methods(Class)}) — annotated methods declared in superclasses are
 *       included, and an override in a subclass replaces the inherited entry</li>
 *   <li>The wire name is {@link #value()} when set, otherwise the Java method name; a name must be
 *       unique per class, so give overloads distinct names explicitly. The name is what the caller
 *       writes; on the wire the method travels as its index in that class's sorted name table, preceded
 *       by the holder-field path to the target — a few bytes in all</li>
 *   <li>The <em>receiving</em> side starts at the object the packet addressed and reads that field path
 *       (index {@code 0} is the object's first holder field, then that holder's, and so on), so a method
 *       declared on a nested holder — an
 *       {@link com.gto.datasynclib.annotations.AdditionalHolder @AdditionalHolder} sub-object, or any
 *       field whose type implements {@link com.gto.datasynclib.IFieldDataHolder} — is invoked on that
 *       nested object, even when it declares the same names as the object a packet addressed</li>
 * </ol>
 *
 * <h3>Arguments and return value</h3>
 * <p>Every parameter must have a {@link com.gto.datasynclib.DataSyncCodec} registered for its
 * type (primitives, {@code String}, enums, arrays and the pre-registered Minecraft types all do).
 * Arguments travel as the codec's <strong>network half</strong> ({@code ByteStreamCodec}, written
 * straight into the call's buffer like a synchronized field), so a call costs what its arguments
 * cost and nothing more; {@code null} is carried by a one-byte present/absent marker instead of a
 * {@code NullData} entry.</p>
 *
 * <p>A remote call has <strong>no return channel</strong>, so an annotated method must be
 * {@code void} — a value-returning method is rejected when its class is scanned
 * ({@link RemoteInvoker#methods(Class)}). A call is fire-and-forget: anything the caller has to
 * observe travels back through the field synchronization instead.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * class MyBlockEntity extends BlockEntity {
 *     @RemoteCall
 *     public void requestUpgrade(int tier) { ... }
 *
 *     @RemoteCall("configure")
 *     private void configure(String key, int value) { ... }
 * }
 *
 * // client side: encode and send
 * RemoteNetwork.callBlockEntityOnServer(be, "requestUpgrade", 3);
 *
 * // server side (done by the packet handler): decode and invoke
 * RemoteInvoker.handle(be, packet.data());
 * }</pre>
 *
 * <p><strong>Trust:</strong> a call arrives from another side, so only annotate methods that are
 * safe to run from there — the framework decodes the arguments with the registered codecs and
 * logs (rather than propagates) failures, but it does not check who sent the call. The invoked
 * method owns its side effects, including {@code setChanged()} for a block entity it mutates.</p>
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
}
