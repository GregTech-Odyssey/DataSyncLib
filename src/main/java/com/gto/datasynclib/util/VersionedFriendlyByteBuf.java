package com.gto.datasynclib.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A stream that also carries the version its payload was written at: the version a versioned file starts
 * with, read off the head of the file or handed to the writer.
 *
 * <p>The version travels with the payload rather than beside it, so a codec that reads a value depends on
 * nothing but its own stream — a decoder can ask {@link #version()} and branch, instead of being told the
 * version by whoever opened the file.</p>
 *
 * <p>{@link FileUtil#writeVersioned} and {@link FileUtil#readVersioned} are the pair that produce and
 * consume one: the writer puts the version at the head of the file as a VarInt and passes this stream to
 * the encoder, the reader takes that VarInt off the head and passes this stream to the decoder. The
 * version is fixed when the stream is made, because a payload's version is decided by whoever wrote it
 * and never changes while it is being read.</p>
 */
public class VersionedFriendlyByteBuf extends FriendlyByteBuf {

    private final int version;

    public VersionedFriendlyByteBuf(ByteBuf buf, int version) {
        super(buf);
        this.version = version;
    }

    /**
     * The version this payload was written at.
     */
    public int version() {
        return version;
    }
}
