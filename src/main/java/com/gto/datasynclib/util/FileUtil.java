package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.StreamDecoder;
import com.gto.datasynclib.datastream.codec.StreamEncoder;
import com.gto.datasynclib.datastream.codec.ValueDecoder;
import com.gto.datasynclib.datastream.codec.ValueEncoder;
import com.gto.datasynclib.datastream.codec.ValueOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import lombok.experimental.UtilityClass;
import net.minecraft.network.FriendlyByteBuf;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A payload as a file: the bytes of a {@link ByteBuf}, or the value of a codec, read and written in one
 * call.
 *
 * <p>A file is written and read in opposite directions, so each method asks for the half it uses — a
 * {@link StreamDecoder} to read, a {@link StreamEncoder} to write — rather than for a whole
 * {@code StreamCodec}. A codec still fits: it is both halves, so it is accepted wherever one is wanted.
 * The same holds for a carrier value: {@link ValueDecoder} and {@link ValueEncoder} here, and a
 * {@code ValueCodec} wherever either is wanted.</p>
 *
 * <h3>No copies</h3>
 * <p>A payload is never copied on its way to or from the file. Reading hands back a buffer
 * <em>wrapped</em> over the bytes that were read, so the codec decodes straight out of them. Writing
 * takes the buffer's nio views, which for every buffer this library produces are views over its own
 * storage rather than copies, and writes those to the channel; a buffer without such a view (a
 * stream-backed one, were one ever added) is written by handing its byte range to a channel stream,
 * which moves them without an array in between. A value is encoded into a buffer this class owns and
 * released afterwards, and the carrier is written and read with
 * {@link ValueOps#writeValue(Object, ByteBuf)} / {@link ValueOps#readValue(ByteBuf)} rather than by
 * turning it into a byte array first.</p>
 *
 * <p>Files are {@link Path}s and the parent directory is created on the way out. Every method throws
 * {@link IOException} the way the java.nio calls it wraps do, and a buffer a method opened is released
 * before it returns or throws.</p>
 */
@UtilityClass
public class FileUtil {

    // ===== bytes =====

    /**
     * The whole file as bytes.
     */
    public byte[] readBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    /**
     * The whole file as a buffer, wrapped over the bytes that were read — no second copy, and the caller
     * releases it.
     */
    public ByteBuf read(Path path) throws IOException {
        return Unpooled.wrappedBuffer(Files.readAllBytes(path));
    }

    /**
     * Writes {@code bytes} to the file, creating its parent directory first.
     */
    public void write(Path path, byte[] bytes) throws IOException {
        createParent(path);
        Files.write(path, bytes);
    }

    /**
     * Writes a buffer's readable bytes — from its reader index, leaving the buffer's own indices alone,
     * so the caller can keep using it.
     */
    public void write(Path path, ByteBuf buf) throws IOException {
        createParent(path);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            var index = buf.readerIndex();
            var length = buf.readableBytes();
            if (buf.nioBufferCount() != 0) {
                // views over the buffer's own storage: the file gets the bytes, not a copy of them
                for (var view : buf.nioBuffers(index, length)) {
                    while (view.hasRemaining()) {
                        channel.write(view);
                    }
                }
                return;
            }
            // no view to hand over (a buffer whose storage is not addressable): stream the byte range
            // out, which still moves the bytes without an array in between
            var out = Channels.newOutputStream(channel);
            buf.getBytes(index, out, length);
            out.flush();
        }
    }

    // ===== a network codec's value =====

    /**
     * The value the file holds, decoded by {@code decoder}.
     *
     * @param decoder a codec that reads from a {@link ByteBuf} — including every
     *                {@code FriendlyByteBuf} one, since the file's buffer is presented as a
     *                {@code FriendlyByteBuf}
     */
    public <T> T read(Path path, StreamDecoder<? super FriendlyByteBuf, T> decoder) throws IOException {
        var buf = read(path);
        try {
            return decoder.decode(new FriendlyByteBuf(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * Encodes {@code value} with {@code encoder} and writes what it produced.
     */
    public <T> void write(Path path, StreamEncoder<? super FriendlyByteBuf, T> encoder, T value) throws IOException {
        // the pool's memory is reused across writes, where a fresh heap array in Unpooled.buffer() is not
        var buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            encoder.encode(new FriendlyByteBuf(buf), value);
            write(path, buf);
        } finally {
            buf.release();
        }
    }

    // ===== a carrier codec's value =====

    /**
     * The value the file holds, decoded by {@code decoder} through {@link JavaValueOps#INSTANCE} — the
     * overload for a payload that does not depend on the version it was written at.
     *
     * @param decoder a codec that reads a stored value, the half a {@code ValueCodec} carries
     */
    public <T> T read(Path path, ValueDecoder<T> decoder) throws IOException {
        return read(path, JavaValueOps.INSTANCE, decoder);
    }

    /**
     * Encodes {@code value} with {@code encoder} through {@link JavaValueOps#INSTANCE} and writes what it
     * produced.
     */
    public <T> void write(Path path, ValueEncoder<T> encoder, T value) throws IOException {
        write(path, JavaValueOps.INSTANCE, encoder, value);
    }

    /**
     * The value {@code ops} reads out of the file — the overload for a payload whose reading depends on
     * the version it was written at, since {@code ops} is where that version lives.
     */
    public <T> T read(Path path, ValueOps ops, ValueDecoder<T> decoder) throws IOException {
        var buf = read(path);
        try {
            return decoder.decode(ops, ops.readValue(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * Encodes {@code value} with {@code encoder} through {@code ops} and writes what it produced, the
     * carrier value going into the buffer the file is written from rather than into an array of its own.
     */
    public <T> void write(Path path, ValueOps ops, ValueEncoder<T> encoder, T value) throws IOException {
        var buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            ops.writeValue(encoder.encode(ops, value), buf);
            write(path, buf);
        } finally {
            buf.release();
        }
    }

    // ===== a versioned file =====

    /**
     * The largest version a versioned file's head may declare. A head that reads as anything outside
     * {@code 1 .. MAX_VERSION} is not one this library wrote, so a plain
     * {@code try { readVersioned(...) } catch (RuntimeException e) { read(...) }} is a working fallback to
     * a file written before versioning — the version is the first thing in the file, which is what makes
     * that possible without a marker, and the bound is what keeps an arbitrary leading byte from being
     * mistaken for one.
     *
     * <p>Version {@code 0} is deliberately not writable: a file that begins with a zero byte is a file
     * whose payload began there, which is exactly the case the fallback has to recognise.</p>
     */
    public static final int MAX_VERSION = 1 << 16;

    /**
     * The value a versioned file holds, decoded by {@code decoder} from a stream that carries the version
     * the head of the file declared.
     *
     * @param decoder a codec reading from a {@link VersionedFriendlyByteBuf}, or from a
     *                {@code FriendlyByteBuf} when it does not care about the version
     * @throws DecoderException if the head does not hold a version this library wrote
     */
    public <T> T readVersioned(Path path, StreamDecoder<? super VersionedFriendlyByteBuf, T> decoder) throws IOException {
        var buf = read(path);
        try {
            var version = readVersion(buf);
            return decoder.decode(new VersionedFriendlyByteBuf(buf, version));
        } finally {
            buf.release();
        }
    }

    /**
     * Encodes {@code value} with {@code encoder} and writes what it produced, with {@code version} at the
     * head of the file as a VarInt.
     *
     * <p>The encoder sees the version it is writing at, so a payload that changed shape between versions
     * writes the shape its own version asks for.</p>
     *
     * @throws IllegalArgumentException if {@code version} is outside {@code 1 .. MAX_VERSION}
     */
    public <T> void writeVersioned(Path path, int version, StreamEncoder<? super VersionedFriendlyByteBuf, T> encoder, T value) throws IOException {
        var buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            writeVersion(buf, version);
            encoder.encode(new VersionedFriendlyByteBuf(buf, version), value);
            write(path, buf);
        } finally {
            buf.release();
        }
    }

    /**
     * The value a versioned file holds, decoded by {@code decoder} through a {@link ValueOps} built at the
     * version the head of the file declared — the carrier's own home for a version, so a codec reads it
     * from {@code ops} instead of from a stream.
     *
     * @throws DecoderException if the head does not hold a version this library wrote
     */
    public <T> T readVersioned(Path path, ValueDecoder<T> decoder) throws IOException {
        var buf = read(path);
        try {
            var ops = JavaValueOps.create(readVersion(buf));
            return decoder.decode(ops, ops.readValue(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * Encodes {@code value} with {@code encoder} through a {@link ValueOps} built at {@code version} and
     * writes what it produced, with {@code version} at the head of the file as a VarInt.
     */
    public <T> void writeVersioned(Path path, int version, ValueEncoder<T> encoder, T value) throws IOException {
        var buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            writeVersion(buf, version);
            var ops = JavaValueOps.create(version);
            ops.writeValue(encoder.encode(ops, value), buf);
            write(path, buf);
        } finally {
            buf.release();
        }
    }

    // ===== a versioned payload in a buffer the caller already holds =====

    /**
     * The value a versioned payload holds, decoded by {@code decoder} out of a buffer whose head is the
     * version — the buffer flavour of {@link #readVersioned(Path, StreamDecoder)}, for a caller that owns
     * the bytes (a payload inside a tag, a file it read itself, a retry at a legacy read).
     *
     * <p>The buffer's reader index is left after the payload, so a caller that wants to retry has to reset
     * it: {@code buf.readerIndex(0)}.</p>
     *
     * @throws DecoderException if the head does not hold a version this library wrote
     */
    public <T> T readVersioned(ByteBuf buf, StreamDecoder<? super VersionedFriendlyByteBuf, T> decoder) {
        var version = readVersion(buf);
        return decoder.decode(new VersionedFriendlyByteBuf(buf, version));
    }

    /**
     * Encodes {@code value} into {@code buf}, with {@code version} at the head of what it writes.
     */
    public <T> void writeVersioned(ByteBuf buf, int version, StreamEncoder<? super VersionedFriendlyByteBuf, T> encoder, T value) {
        writeVersion(buf, version);
        encoder.encode(new VersionedFriendlyByteBuf(buf, version), value);
    }

    /**
     * The value a versioned payload holds, decoded by {@code decoder} through a {@link ValueOps} built at
     * the version the head of the buffer declared.
     *
     * @throws DecoderException if the head does not hold a version this library wrote
     */
    public <T> T readVersioned(ByteBuf buf, ValueDecoder<T> decoder) {
        var ops = JavaValueOps.create(readVersion(buf));
        return decoder.decode(ops, ops.readValue(buf));
    }

    /**
     * Encodes {@code value} into {@code buf} through a {@link ValueOps} built at {@code version}, with
     * {@code version} at the head of what it writes.
     */
    public <T> void writeVersioned(ByteBuf buf, int version, ValueEncoder<T> encoder, T value) {
        writeVersion(buf, version);
        var ops = JavaValueOps.create(version);
        ops.writeValue(encoder.encode(ops, value), buf);
    }

    private static void writeVersion(ByteBuf buf, int version) {
        if (version < 1 || version > MAX_VERSION) {
            throw new IllegalArgumentException("A versioned file's version must be in 1.." + MAX_VERSION + ", got " + version);
        }
        VarInts.write(buf, version);
    }

    private static int readVersion(ByteBuf buf) {
        var version = VarInts.read(buf);
        if (version < 1 || version > MAX_VERSION) {
            throw new DecoderException("Not a versioned file: its head reads as version " + version);
        }
        return version;
    }

    private void createParent(Path path) throws IOException {
        var parent = path.getParent();
        // a directory that is already there is the common case, and asking createDirectories costs a
        // failed create per level; one stat answers it
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
