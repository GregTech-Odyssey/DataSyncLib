package com.gto.datasynclib.util;

import com.google.common.util.concurrent.AtomicDouble;
import com.google.gson.*;
import com.gto.datasynclib.datastream.codec.CustomTypes;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON as a registered {@link CustomTypes.Type} — the same arrangement {@link NbtUtil} uses for tags: the
 * carrier never looks inside the value, it stores the registered id and the payload this class writes, so a
 * {@link JsonElement} travels as the JSON it already is instead of being flattened into a string on the way
 * out and parsed again on the way in.
 *
 * <p>The payload is a compact binary form rather than the JSON text: one kind byte — null, primitive,
 * array or object — and then that kind's payload, with a primitive keeping the Java type it was built with.
 * That type is {@link #VALUE read out of the primitive's own field} rather than asked of Gson, so writing one
 * is a field read and a switch. A {@code byte} stays a byte, an {@link AtomicLong} stays a long, and a number
 * the payload has no type for
 * — what the reader gives every number in a document — is narrowed by size, an integer through {@code byte},
 * {@code short}, {@code int}, {@code long} and {@link BigInteger}, a decimal through {@code float},
 * {@code double} and {@link BigDecimal}. A {@link BigInteger} is its two's-complement bytes and a
 * {@link BigDecimal} its unscaled bytes plus the scale, the payloads
 * {@code ValueOpsConverters} stores for them. Arrays and objects are a {@link VarInts VarInt} count followed
 * by their elements; an object key is a
 * {@link JavaValueOps#writeString(ByteBuf, String) length-prefixed} UTF-8 string.</p>
 *
 * <p>{@link #JSON_TYPE} is for an element whose type is not known statically; {@link #JSON_OBJECT_TYPE} and
 * {@link #JSON_ARRAY_TYPE} are the same payload without the kind byte, for the common case where it is —
 * the pair of choices {@link NbtUtil#TAG_TYPE} and {@link NbtUtil#COMPOUND_TAG_TYPE} offer.</p>
 */
@UtilityClass
public class JsonUtils {

    /**
     * The field a {@link JsonPrimitive} keeps its value in. Reading it is one field read — a primitive's own
     * {@code isBoolean()}, {@code isString()}, {@code isNumber()} and {@code getAsNumber()} each test this
     * same field again, and the switch below needs the value itself, not six answers about it.
     */
    private final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.privateLookupIn(JsonPrimitive.class, MethodHandles.lookup())
                    .findVarHandle(JsonPrimitive.class, "value", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // the kind of a complete element
    private final byte KIND_NULL = 0;
    private final byte KIND_PRIMITIVE = 1;
    private final byte KIND_ARRAY = 2;
    private final byte KIND_OBJECT = 3;

    // the Java type a primitive holds, so a number keeps the width it was built with
    private final byte PRIMITIVE_NULL = 0;
    private final byte PRIMITIVE_BOOLEAN = 1;
    private final byte PRIMITIVE_BYTE = 2;
    private final byte PRIMITIVE_SHORT = 3;
    private final byte PRIMITIVE_INT = 4;
    private final byte PRIMITIVE_LONG = 5;
    private final byte PRIMITIVE_FLOAT = 6;
    private final byte PRIMITIVE_DOUBLE = 7;
    private final byte PRIMITIVE_BIG_INTEGER = 8;
    private final byte PRIMITIVE_BIG_DECIMAL = 9;
    private final byte PRIMITIVE_STRING = 10;

    /**
     * The significant digits a decimal literal may have and still round-trip through a {@code double} — the
     * guarantee that lets an ordinary number in a document become a {@code double} without a
     * {@link BigDecimal} ever being built to check it.
     */
    private final int EXACT_DIGITS = 15;

    /**
     * Any {@link JsonElement}: the kind byte and then its payload, so the reader knows what follows.
     */
    public final CustomTypes.Type<JsonElement> JSON_TYPE = CustomTypes.Type.<JsonElement>builder(3)
            .write(JsonUtils::write)
            .read(JsonUtils::read)
            .build();

    /**
     * A {@link JsonObject} without the kind byte — one byte less when the type is known.
     */
    public final CustomTypes.Type<JsonObject> JSON_OBJECT_TYPE = CustomTypes.Type.<JsonObject>builder(4)
            .write(JsonUtils::writeObject)
            .read(JsonUtils::readObject)
            .build();

    /**
     * A {@link JsonArray} without the kind byte.
     */
    public final CustomTypes.Type<JsonArray> JSON_ARRAY_TYPE = CustomTypes.Type.<JsonArray>builder(5)
            .write(JsonUtils::writeArray)
            .read(JsonUtils::readArray)
            .build();

    /**
     * Writes a complete element: its kind byte, then that kind's payload.
     */
    public void write(JsonElement json, ByteBuf buf) {
        switch (json) {
            case JsonNull ignored -> buf.writeByte(KIND_NULL);
            case JsonPrimitive primitive -> {
                buf.writeByte(KIND_PRIMITIVE);
                writePrimitive(primitive, buf);
            }
            case JsonArray array -> {
                buf.writeByte(KIND_ARRAY);
                writeArray(array, buf);
            }
            case JsonObject object -> {
                buf.writeByte(KIND_OBJECT);
                writeObject(object, buf);
            }
            default -> throw new IllegalArgumentException("Unsupported JsonElement: " + json.getClass().getName());
        }
    }

    /**
     * Reads a {@link #write(JsonElement, ByteBuf)} payload back.
     */
    public JsonElement read(ByteBuf buf) {
        var kind = buf.readByte();
        return switch (kind) {
            case KIND_NULL -> JsonNull.INSTANCE;
            case KIND_PRIMITIVE -> readPrimitive(buf);
            case KIND_ARRAY -> readArray(buf);
            case KIND_OBJECT -> readObject(buf);
            default -> throw new IllegalArgumentException("Unknown JSON kind byte " + kind);
        };
    }

    /**
     * Writes a primitive's payload: the Java type it holds, then the value.
     *
     * <p>The type is the one {@link #VALUE} reads out of the primitive's own field, so this is one field read
     * and one switch. Asking the primitive {@code isBoolean()}, {@code isString()} and {@code isNumber()} in
     * turn would be three virtual calls that each test that same field again, and {@code getAsNumber()} a
     * fourth.</p>
     */
    public void writePrimitive(JsonPrimitive primitive, ByteBuf buf) {
        var held = VALUE.get(primitive);
        switch (held) {
            case null -> buf.writeByte(PRIMITIVE_NULL);
            case Boolean value -> {
                buf.writeByte(PRIMITIVE_BOOLEAN);
                buf.writeBoolean(value);
            }
            case String value -> {
                buf.writeByte(PRIMITIVE_STRING);
                JavaValueOps.writeString(buf, value);
            }
            case Character value -> {
                // Gson turns a char into a String when the primitive is built; one that kept it is that string
                buf.writeByte(PRIMITIVE_STRING);
                JavaValueOps.writeString(buf, value.toString());
            }
            case Byte value -> {
                buf.writeByte(PRIMITIVE_BYTE);
                buf.writeByte(value);
            }
            case Short value -> {
                buf.writeByte(PRIMITIVE_SHORT);
                buf.writeShort(value);
            }
            case Integer value -> {
                buf.writeByte(PRIMITIVE_INT);
                buf.writeInt(value);
            }
            case Long value -> {
                buf.writeByte(PRIMITIVE_LONG);
                buf.writeLong(value);
            }
            case Float value -> {
                buf.writeByte(PRIMITIVE_FLOAT);
                buf.writeFloat(value);
            }
            case Double value -> {
                buf.writeByte(PRIMITIVE_DOUBLE);
                buf.writeDouble(value);
            }
            case BigInteger value -> {
                buf.writeByte(PRIMITIVE_BIG_INTEGER);
                JavaValueOps.writeByteArray(buf, value.toByteArray());
            }
            case BigDecimal value -> {
                buf.writeByte(PRIMITIVE_BIG_DECIMAL);
                writeBigDecimal(value, buf);
            }
            case AtomicInteger value -> {
                buf.writeByte(PRIMITIVE_INT);
                buf.writeInt(value.intValue());
            }
            case AtomicLong value -> {
                buf.writeByte(PRIMITIVE_LONG);
                buf.writeLong(value.longValue());
            }
            case AtomicDouble value -> {
                buf.writeByte(PRIMITIVE_DOUBLE);
                buf.writeDouble(value.doubleValue());
            }
            case Number value -> writeNumberText(value, buf);
            default -> throw new IllegalArgumentException(
                    "Unsupported JsonPrimitive value: " + held.getClass().getName());
        }
    }

    /**
     * A number whose Java type the payload has no id for — Gson's reader hands one out for every number in a
     * document, and any other {@link Number} arrives here too. It travels as the smallest type its text fits:
     * an integer through {@code byte}, {@code short}, {@code int}, {@code long} and {@link BigInteger}, a
     * decimal through {@code float}, {@code double} and {@link BigDecimal}.
     *
     * <p>A literal a {@code double} holds — which is every number in a document bar the few written wider than
     * a double — never builds a {@link BigDecimal}: {@link #holdsExactly} answers from the text's digits, and
     * only a literal that needs the exact comparison pays for it.</p>
     */
    private void writeNumberText(Number number, ByteBuf buf) {
        var text = number.toString();
        if (isInteger(text)) {
            writeIntegerText(text, buf);
            return;
        }
        var value = Double.parseDouble(text);
        if (holdsExactly(text, value)) {
            if ((float) value == value) {
                buf.writeByte(PRIMITIVE_FLOAT);
                buf.writeFloat((float) value);
            } else {
                buf.writeByte(PRIMITIVE_DOUBLE);
                buf.writeDouble(value);
            }
            return;
        }
        writeWideDecimal(text, value, buf);
    }

    /**
     * Writes a literal a {@code double} does not hold, as a {@link BigDecimal} — or as a {@code double} when
     * the text is not a decimal literal at all ({@code NaN}, an infinity, a hex float) and a double is the
     * only thing that carries it.
     */
    private void writeWideDecimal(String text, double value, ByteBuf buf) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException notADecimal) {
            buf.writeByte(PRIMITIVE_DOUBLE);
            buf.writeDouble(value);
            return;
        }
        // a literal with more digits than a double prints can still be the value of one, and only the exact
        // comparison can say so
        if (Double.isFinite(value) && decimal.compareTo(BigDecimal.valueOf(value)) == 0) {
            buf.writeByte(PRIMITIVE_DOUBLE);
            buf.writeDouble(value);
            return;
        }
        buf.writeByte(PRIMITIVE_BIG_DECIMAL);
        writeBigDecimal(decimal, buf);
    }

    /**
     * An integer literal, written as the narrowest of {@code byte}, {@code short}, {@code int} and
     * {@code long} that holds it, or as a {@link BigInteger} when none does.
     */
    private void writeIntegerText(String text, ByteBuf buf) {
        try {
            var value = Long.parseLong(text);
            if ((byte) value == value) {
                buf.writeByte(PRIMITIVE_BYTE);
                buf.writeByte((byte) value);
            } else if ((short) value == value) {
                buf.writeByte(PRIMITIVE_SHORT);
                buf.writeShort((short) value);
            } else if ((int) value == value) {
                buf.writeByte(PRIMITIVE_INT);
                buf.writeInt((int) value);
            } else {
                buf.writeByte(PRIMITIVE_LONG);
                buf.writeLong(value);
            }
        } catch (NumberFormatException tooBig) {
            buf.writeByte(PRIMITIVE_BIG_INTEGER);
            JavaValueOps.writeByteArray(buf, new BigInteger(text).toByteArray());
        }
    }

    /**
     * A {@link BigDecimal} as the bytes of its unscaled value and then its scale — the payload
     * {@code ValueOpsConverters.BIG_DECIMAL} stores for one, both halves a {@link VarInts VarInt} the same
     * way, since a number written in JSON can carry any exponent.
     */
    private void writeBigDecimal(BigDecimal value, ByteBuf buf) {
        JavaValueOps.writeByteArray(buf, value.unscaledValue().toByteArray());
        VarInts.write(buf, value.scale());
    }

    /**
     * Reads a {@link #writeBigDecimal(BigDecimal, ByteBuf)} payload back.
     */
    private BigDecimal readBigDecimal(ByteBuf buf) {
        return new BigDecimal(new BigInteger(JavaValueOps.readByteArray(buf)), VarInts.read(buf));
    }

    /**
     * Whether a {@code double} already holds {@code text}'s own value, answered from the text so that the
     * number that fills a document costs no {@link BigDecimal}: a literal of at most
     * {@value #EXACT_DIGITS} significant digits round-trips through a double, and one whose value stayed
     * inside the normal range cannot have lost a digit on the way. Everything the digits cannot vouch for is
     * left to {@link #writeWideDecimal(String, double, ByteBuf)} — a literal written wider than a double
     * keeps, one that fell to a subnormal or to zero ({@code 1e-400} is not zero), an infinity, or text that
     * is no decimal literal at all — and that is the only path that pays for the exact comparison.
     */
    private boolean holdsExactly(String text, double value) {
        if (!Double.isFinite(value)) return false;
        if (value == 0.0) return !hasNonZeroDigit(text);
        return Math.abs(value) >= Double.MIN_NORMAL && significantDigits(text) <= EXACT_DIGITS;
    }

    /**
     * The significant digits of a decimal literal's mantissa — the digits a {@code double} has to keep.
     * The sign and the point are not digits, leading zeros only push the first digit along, trailing zeros
     * add nothing, and the exponent is skipped because it only moves the point.
     */
    private int significantDigits(String text) {
        var count = 0;
        var trailingZeros = 0;
        var seen = false;
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == 'e' || c == 'E') break;
            if (c < '0' || c > '9') continue;
            if (c != '0') {
                seen = true;
                count++;
                trailingZeros = 0;
            } else if (seen) {
                count++;
                trailingZeros++;
            }
        }
        return count - trailingZeros;
    }

    /**
     * Whether a decimal literal's mantissa holds a non-zero digit — what tells {@code 1e-400} from {@code 0.0}
     * once a {@code double} has rounded both to zero.
     */
    private boolean hasNonZeroDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == 'e' || c == 'E') return false;
            if (c >= '1' && c <= '9') return true;
        }
        return false;
    }

    /**
     * Whether {@code text} is an integer literal — no fraction and no exponent.
     */
    private boolean isInteger(String text) {
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') return false;
        }
        return true;
    }

    /**
     * Reads a {@link #writePrimitive(JsonPrimitive, ByteBuf)} payload back.
     */
    public JsonPrimitive readPrimitive(ByteBuf buf) {
        var type = buf.readByte();
        return switch (type) {
            case PRIMITIVE_NULL -> new JsonPrimitive((String) null);
            case PRIMITIVE_BOOLEAN -> new JsonPrimitive(buf.readBoolean());
            case PRIMITIVE_BYTE -> new JsonPrimitive(buf.readByte());
            case PRIMITIVE_SHORT -> new JsonPrimitive(buf.readShort());
            case PRIMITIVE_INT -> new JsonPrimitive(buf.readInt());
            case PRIMITIVE_LONG -> new JsonPrimitive(buf.readLong());
            case PRIMITIVE_FLOAT -> new JsonPrimitive(buf.readFloat());
            case PRIMITIVE_DOUBLE -> new JsonPrimitive(buf.readDouble());
            case PRIMITIVE_BIG_INTEGER -> new JsonPrimitive(new BigInteger(JavaValueOps.readByteArray(buf)));
            case PRIMITIVE_BIG_DECIMAL -> new JsonPrimitive(readBigDecimal(buf));
            case PRIMITIVE_STRING -> new JsonPrimitive(JavaValueOps.readString(buf));
            default -> throw new IllegalArgumentException("Unknown JSON primitive type byte " + type);
        };
    }

    /**
     * Writes an array's payload: the element count, then every element in full.
     */
    public void writeArray(JsonArray array, ByteBuf buf) {
        VarInts.write(buf, array.size());
        for (var element : array) {
            write(element, buf);
        }
    }

    /**
     * Reads a {@link #writeArray(JsonArray, ByteBuf)} payload back.
     */
    public JsonArray readArray(ByteBuf buf) {
        var size = VarInts.read(buf);
        var array = new JsonArray(size);
        for (int i = 0; i < size; i++) {
            array.add(read(buf));
        }
        return array;
    }

    /**
     * Writes an object's payload: the entry count, then every key and value.
     */
    public void writeObject(JsonObject object, ByteBuf buf) {
        VarInts.write(buf, object.size());
        for (var entry : object.entrySet()) {
            JavaValueOps.writeString(buf, entry.getKey());
            write(entry.getValue(), buf);
        }
    }

    /**
     * Reads a {@link #writeObject(JsonObject, ByteBuf)} payload back.
     */
    public JsonObject readObject(ByteBuf buf) {
        var size = VarInts.read(buf);
        var object = new JsonObject();
        for (int i = 0; i < size; i++) {
            object.add(JavaValueOps.readString(buf), read(buf));
        }
        return object;
    }
}
