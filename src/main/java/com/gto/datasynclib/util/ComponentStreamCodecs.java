package com.gto.datasynclib.util;

import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.datastream.codec.StreamCodec;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.experimental.UtilityClass;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.BlockDataSource;
import net.minecraft.network.chat.contents.DataSource;
import net.minecraft.network.chat.contents.EntityDataSource;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.NbtContents;
import net.minecraft.network.chat.contents.ScoreContents;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.StorageDataSource;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The two forms of a {@link Component}: {@link #COMPONENT}, the binary layout the network uses, and
 * {@link #JSON}, the vanilla JSON string a {@code FriendlyByteBuf#writeComponent} writes.
 *
 * <p>A component is a small tree of records - a contents object, a list of siblings and a style - and the
 * JSON form spells all of that out: a field name, quotes and escaping per node, and a UTF-8 length for the
 * whole string. A tooltip of a dozen words therefore travels as hundreds of bytes on every packet that
 * carries it. The binary form writes the same tree as a kind byte per contents, that kind's own fields, a
 * VarInt sibling count followed by those siblings, and finally the style; the text is the only thing that
 * still travels as characters, and it is written once. Nothing is written for a style that has none of its
 * parts set, so a plain line costs four bytes of framing plus the length and the text of the one string it
 * is.</p>
 *
 * <p>The layout, per node:</p>
 * <pre>
 * component := contents siblings style
 * contents  := byte kind, then that kind's fields
 * siblings  := VarInt count, then that many components
 * style     := byte attributes, byte presence, then each present part
 * </pre>
 *
 * <p>The kinds are literal ({@code 0}), translatable ({@code 1}), keybind ({@code 2}), score ({@code 3}),
 * selector ({@code 4}), nbt ({@code 5}), "some other contents" ({@code 6}) and a registered contents
 * ({@code 7}, see {@link #register}). The attributes byte holds
 * the five three-state flags (absent, false, true) the way {@link Style} keeps them; two bits per flag
 * would need ten, so they are packed as one base-3 number instead, which fits the byte the header has.</p>
 *
 * <p>The flags and the font come out of {@link Style}'s own fields through a private lookup that a runtime
 * may refuse ({@link #RAW_ATTRIBUTES}). Where it works - the classpath, and any runtime that opens
 * {@code net.minecraft.network.chat} to this class - all three states and an explicitly set font travel
 * exactly. Where it does not, the codec writes the reduced form instead of failing: true stays true,
 * explicit false and absent both travel as absent, and no font is written. That form is what the getters
 * and {@link Style#equals} can see anyway; only the raw field and the JSON a style writes can tell the
 * difference.</p>
 *
 * <h3>Registered contents</h3>
 * <p>A mod's own {@link ComponentContents} has no kind of its own here, and 1.20.1 has no contents type
 * codec to fall back on either, so such a node would travel as kind {@code 6} - the text it renders, which
 * comes back a literal and loses everything the object was. {@link #register} replaces that with the class's
 * own codec: the node is written as kind {@code 7}, then the class's {@link Class#getName() name} as a
 * {@code Utf} key, then a VarInt payload length, then exactly that many bytes of the registered codec's
 * output.</p>
 *
 * <p>The key is the class name rather than a number handed out here, so two sides that registered in
 * different orders still agree on what a key means, and a component written by one build reads in another.
 * Both sides need the class registered - nothing about the ids is negotiated, the key is - and a peer that
 * does not have it steps over the framed payload, which is the one thing the length is there for: the
 * reader knows where the payload ends without knowing how to read it. It names that key in a warning once,
 * and that node decodes as {@link Component#empty()} while its style and its siblings still read, so one
 * unknown line in a tooltip does not cost the packet.</p>
 *
 * <p>What comes back is the registered object itself: the decoded contents is made into the node with
 * {@link MutableComponent#create(ComponentContents)} and the style and siblings are set on that, which is
 * the same call a mod makes when it builds the component in the first place. There is no text in between,
 * so nothing of the object is lost.</p>
 *
 * <p>Registration is meant for mod construction, before the codec is first used: it fills plain static
 * tables, like the lookup the style attributes are read through, and it is not thread safe.</p>
 *
 * <h3>What cannot travel exactly</h3>
 * <p>Each of these is the most a peer that does not have the original value can still show, and each is
 * confined to what it names - the node's style and siblings are unaffected:</p>
 * <ul>
 *   <li>an explicit {@code false} attribute, and a font set to the default one, survive only where the raw
 *       fields are reachable: otherwise they travel in the reduced form above, which no getter can tell
 *       from the original;</li>
 *   <li>a contents object this codec does not know (1.20.1 has no contents type codec to fall back on, and
 *       vanilla's own serializer refuses one outright) travels as the text it renders, and comes back as a
 *       literal - or, when it renders nothing, as {@link Component#empty()}, which is the contents the JSON
 *       form of {@code {"text":""}} builds and the root of every {@code Component.empty().append(...)}. A
 *       class registered with {@link #register} is not one of these: it travels as itself;</li>
 *   <li>the {@code color} of a style travels as its RGB, so a named colour ({@code "red"}) comes back as
 *       the value it names and serializes again as {@code "#FF5555"} - the same colour, not the same
 *       spelling;</li>
 *   <li>an absent {@code fallback} and an empty one are both written as the empty string, so an explicitly
 *       empty fallback comes back as absent;</li>
 *   <li>a hover event whose action is not one of vanilla's three, or whose entity type has no registry key,
 *       travels as the plain-text hover of what it renders.</li>
 * </ul>
 *
 * <p>{@link #JSON} is not a leftover: a save file written by one session is read by another whose registry
 * order, mods or data may differ, and it is meant to be read by a person too, which is why
 * {@link DiskByteBufCodecs} publishes it as its {@code COMPONENT_CODEC} and the wire keeps the binary one.
 * The JSON path is the vanilla calls unchanged, so a file's bytes do not change at all.</p>
 */
@UtilityClass
public class ComponentStreamCodecs {

    /**
     * The binary wire codec: {@link #write(FriendlyByteBuf, Component) write} then
     * {@link #read(FriendlyByteBuf) read}, the layout the class documentation describes.
     */
    public final StreamCodec<FriendlyByteBuf, Component> COMPONENT = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Component obj) {
            write(buf, obj);
        }

        @Override
        public Component decode(FriendlyByteBuf buf) {
            return read(buf);
        }
    };

    /**
     * The vanilla JSON string, for a save file: {@code writeComponent} / {@code readComponent}, which is
     * what {@link ByteBufCodecExtends#COMPONENT_CODEC} used to be for both directions.
     */
    public final StreamCodec<FriendlyByteBuf, Component> JSON = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Component obj) {
            buf.writeComponent(obj);
        }

        @Override
        public Component decode(FriendlyByteBuf buf) {
            return buf.readComponent();
        }
    };

    /**
     * Registers the codec of one contents class, so a component carrying it travels as that class's own
     * bytes - kind {@code 7} - instead of as kind {@code 6}'s flattening to the text it renders. The class
     * documentation describes the layout and what a peer without the class does with it.
     *
     * <p>The wire key is {@link Class#getName() the class's name}, not a number handed out here, so
     * registration order does not matter and two sides agree as long as both have that class. The class is
     * looked up exactly, by the class of the contents being written: a subclass of a registered class is not
     * covered by its registration and travels as kind {@code 6} unless it is registered too.</p>
     *
     * <p>Register before the codec is first used. The tables this fills are static state, like the rest of
     * this class's initialization, and neither this call nor a concurrent encode/decode is safe against the
     * other.</p>
     *
     * @param type  the contents class the codec reads and writes, and the key it travels under
     * @param codec the codec of that class; it has to read back exactly the bytes it writes
     * @param <T>   the contents class
     * @throws IllegalArgumentException if {@code type} or {@code codec} is null, or a codec is already
     *                                  registered for exactly that class
     */
    public static <T extends ComponentContents> void register(Class<T> type,
            StreamCodec<? super FriendlyByteBuf, T> codec) {
        if (type == null) {
            throw new IllegalArgumentException("A registered component contents needs the class it is keyed by");
        }
        if (codec == null) {
            throw new IllegalArgumentException("The registered component contents " + type.getName()
                    + " needs a codec");
        }
        var key = type.getName();
        if (BY_CLASS.containsKey(type)) {
            throw new IllegalArgumentException("A codec is already registered for " + key
                    + ", so a second one for that class would never be reached");
        }
        // writeContents only reaches an entry with an instance of exactly the class it was registered for,
        // so the cast is the registry's own invariant rather than an assumption about the codec
        var registration = new Registration(key,
                (StreamCodec<? super FriendlyByteBuf, ComponentContents>) codec);
        BY_CLASS.put(type, registration);
        BY_KEY.put(key, registration);
    }

    // contents kinds
    private final byte CONTENTS_LITERAL = 0;
    private final byte CONTENTS_TRANSLATABLE = 1;
    private final byte CONTENTS_KEYBIND = 2;
    private final byte CONTENTS_SCORE = 3;
    private final byte CONTENTS_SELECTOR = 4;
    private final byte CONTENTS_NBT = 5;
    private final byte CONTENTS_UNKNOWN = 6;
    private final byte CONTENTS_REGISTERED = 7;

    // kinds of one translatable argument; 3 is the list, 2 stays unused
    private final byte ARGUMENT_COMPONENT = 0;
    private final byte ARGUMENT_TEXT = 1;
    private final byte ARGUMENT_COMPONENTS = 3;

    // kinds of an nbt data source
    private final byte SOURCE_BLOCK = 0;
    private final byte SOURCE_ENTITY = 1;
    private final byte SOURCE_STORAGE = 2;

    // a hover event as it is written: the action's index, then the kind of the value it carries. The two
    // are the same number because HOVER_ACTIONS is in this order, so the kinds are aliases of the indices
    private final byte ACTION_TEXT = 0;
    private final byte ACTION_ITEM = 1;
    private final byte ACTION_ENTITY = 2;
    private final byte HOVER_TEXT = ACTION_TEXT;
    private final byte HOVER_ITEM = ACTION_ITEM;
    private final byte HOVER_ENTITY = ACTION_ENTITY;

    // the parts a style may carry, in the order they are written
    private final int PRESENT_COLOR = 1;
    private final int PRESENT_INSERTION = 2;
    private final int PRESENT_FONT = 4;
    private final int PRESENT_CLICK = 8;
    private final int PRESENT_HOVER = 16;

    /**
     * The number of distinct attribute headers: five flags with three states each.
     */
    private final int ATTRIBUTE_STATES = 3 * 3 * 3 * 3 * 3;

    /**
     * Vanilla's hover actions in the order their indices are written. {@link HoverEvent.Action} is a plain
     * class rather than an enum, so there is no {@code ordinal()} to write and no {@code values()} to read
     * back; the wire keeps one byte an action by numbering these three instead of writing their names.
     */
    private final HoverEvent.Action<?>[] HOVER_ACTIONS = {
            HoverEvent.Action.SHOW_TEXT, HoverEvent.Action.SHOW_ITEM, HoverEvent.Action.SHOW_ENTITY
    };

    /**
     * The five flags of a {@link Style}, read out of their own fields: {@link Style#isBold()} answers whether
     * the flag is <em>true</em> and folds the other two states into the same {@code false}, and
     * {@link Style#equals} asks that getter - yet the JSON a style writes spells them differently
     * ({@code "bold":false} against no {@code bold} at all), so the wire has to keep them apart. The fields
     * are read the way {@link JsonUtils} reads a {@link com.google.gson.JsonPrimitive}'s value, for the same
     * reason: one read of what is there beats a getter that has already forgotten half of it.
     *
     * <p>The read is best effort - see {@link #RAW_ATTRIBUTES}: where a runtime does not let this class
     * reach the fields, the handles stay null and the codec writes the reduced form instead.</p>
     */
    private final VarHandle BOLD;
    private final VarHandle ITALIC;
    private final VarHandle UNDERLINED;
    private final VarHandle STRIKETHROUGH;
    private final VarHandle OBFUSCATED;

    /**
     * The font field, for the same reason: {@link Style#getFont()} never returns null (it falls back to
     * {@code minecraft:default}), so neither it nor {@link Style#equals} can say whether a font was set at
     * all. Without the field, every styled node would carry a font it never had.
     */
    private final VarHandle FONT;

    /**
     * Whether the six fields above could be reached. Reading them needs a private lookup into {@link Style},
     * which a runtime may refuse: {@code net.minecraft.network.chat} has to be open to this class's module,
     * and the game ships its classes as a module of their own.
     *
     * <p>A refusal must not be fatal. This class is initialized by whoever first touches the codec, so an
     * exception in the static initializer would reach every consumer as an
     * {@code ExceptionInInitializerError}, for the sake of a tooltip's style. The lookup is therefore best
     * effort, and the codec keeps the same wire format in a reduced form where it failed: true stays true,
     * false and "inherit" both travel as "inherit", and no font is written. That is exactly what the getters
     * and {@link Style#equals} can see anyway. Both ends of a connection run this code against their own
     * runtime, and the header is one byte either way.</p>
     */
    private final boolean RAW_ATTRIBUTES;

    static {
        VarHandle bold = null;
        VarHandle italic = null;
        VarHandle underlined = null;
        VarHandle strikethrough = null;
        VarHandle obfuscated = null;
        VarHandle font = null;
        boolean raw = false;
        try {
            var style = MethodHandles.privateLookupIn(Style.class, MethodHandles.lookup());
            bold = style.findVarHandle(Style.class, "bold", Boolean.class);
            italic = style.findVarHandle(Style.class, "italic", Boolean.class);
            underlined = style.findVarHandle(Style.class, "underlined", Boolean.class);
            strikethrough = style.findVarHandle(Style.class, "strikethrough", Boolean.class);
            obfuscated = style.findVarHandle(Style.class, "obfuscated", Boolean.class);
            font = style.findVarHandle(Style.class, "font", ResourceLocation.class);
            raw = true;
        } catch (ReflectiveOperationException | RuntimeException | Error notReachable) {
            // the package is not open to us: write the reduced form rather than fail the whole class
        }
        BOLD = bold;
        ITALIC = italic;
        UNDERLINED = underlined;
        STRIKETHROUGH = strikethrough;
        OBFUSCATED = obfuscated;
        FONT = font;
        RAW_ATTRIBUTES = raw;
    }

    /**
     * The registrations keyed by the exact class of the contents being written - an identity lookup, the
     * cheapest a class-keyed table gets, and the one
     * {@link com.gto.datasynclib.datastream.codec.ValueConverters} uses for its own exact path. No predicate
     * search sits in front of it, so there is no {@link ClassValue} cache to put here either: a registration
     * covers its own class alone, and one hash answers that.
     */
    private final Reference2ReferenceOpenHashMap<Class<?>, Registration> BY_CLASS = new Reference2ReferenceOpenHashMap<>();

    /**
     * The same registrations keyed by the name that travels on the wire, because that name is all the reader
     * has.
     */
    private final Object2ReferenceOpenHashMap<String, Registration> BY_KEY = new Object2ReferenceOpenHashMap<>();

    /**
     * The keys already reported missing, so a peer with a different mod set warns once per key rather than
     * once per component: a tooltip of twenty lines would otherwise write twenty warnings.
     */
    private final ObjectOpenHashSet<String> REPORTED = new ObjectOpenHashSet<>();

    /**
     * One registration: the key written on the wire and the codec that carries the contents. The codec is
     * held as one for any {@link ComponentContents}, since the exact-class lookup that found the entry is
     * what decides which class it is handed.
     */
    private record Registration(String key, StreamCodec<? super FriendlyByteBuf, ComponentContents> codec) {
    }

    /**
     * Writes a component tree: the node's own contents, then its siblings, then its style - the order the
     * reader needs, since a sibling is a whole component of its own.
     */
    private void write(FriendlyByteBuf buf, Component component) {
        writeContents(buf, component.getContents());
        var siblings = component.getSiblings();
        buf.writeVarInt(siblings.size());
        for (var sibling : siblings) {
            write(buf, sibling);
        }
        writeStyle(buf, component.getStyle());
    }

    /**
     * Reads a {@link #write(FriendlyByteBuf, Component)} payload back. The siblings are the tree's shape
     * and the style is only this node's, so the style is set on the node the siblings were appended to.
     */
    private MutableComponent read(FriendlyByteBuf buf) {
        var base = readContents(buf);
        var count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            base.append(read(buf));
        }
        return base.setStyle(readStyle(buf));
    }

    private void writeContents(FriendlyByteBuf buf, ComponentContents contents) {
        switch (contents) {
            case LiteralContents literal -> {
                buf.writeByte(CONTENTS_LITERAL);
                buf.writeUtf(literal.text());
            }
            case TranslatableContents translatable -> {
                buf.writeByte(CONTENTS_TRANSLATABLE);
                buf.writeUtf(translatable.getKey());
                var fallback = translatable.getFallback();
                // the empty string stands in for an absent fallback, which is the one thing a key cannot be
                buf.writeUtf(fallback == null ? "" : fallback);
                var args = translatable.getArgs();
                buf.writeVarInt(args.length);
                for (var arg : args) {
                    writeArgument(buf, arg);
                }
            }
            case KeybindContents keybind -> {
                buf.writeByte(CONTENTS_KEYBIND);
                buf.writeUtf(keybind.getName());
            }
            case ScoreContents score -> {
                buf.writeByte(CONTENTS_SCORE);
                buf.writeUtf(score.getName());
                buf.writeUtf(score.getObjective());
            }
            case SelectorContents selector -> {
                buf.writeByte(CONTENTS_SELECTOR);
                buf.writeUtf(selector.getPattern());
                writeOptional(buf, selector.getSeparator());
            }
            case NbtContents nbt -> {
                buf.writeByte(CONTENTS_NBT);
                buf.writeUtf(nbt.getNbtPath());
                buf.writeBoolean(nbt.isInterpreting());
                writeOptional(buf, nbt.getSeparator());
                writeDataSource(buf, nbt.getDataSource());
            }
            default -> {
                var registration = BY_CLASS.get(contents.getClass());
                if (registration != null) {
                    writeRegistered(buf, registration, contents);
                } else {
                    // a contents class this codec does not know: the text it renders is what a peer can show
                    buf.writeByte(CONTENTS_UNKNOWN);
                    buf.writeUtf(renderedText(contents));
                }
            }
        }
    }

    private MutableComponent readContents(FriendlyByteBuf buf) {
        var kind = buf.readByte();
        return switch (kind) {
            case CONTENTS_LITERAL -> Component.literal(buf.readUtf());
            case CONTENTS_TRANSLATABLE -> readTranslatable(buf);
            case CONTENTS_KEYBIND -> Component.keybind(buf.readUtf());
            case CONTENTS_SCORE -> Component.score(buf.readUtf(), buf.readUtf());
            case CONTENTS_SELECTOR -> Component.selector(buf.readUtf(), readOptional(buf));
            case CONTENTS_NBT -> Component.nbt(buf.readUtf(), buf.readBoolean(), readOptional(buf), readDataSource(buf));
            case CONTENTS_UNKNOWN -> fromText(buf.readUtf());
            case CONTENTS_REGISTERED -> readRegistered(buf);
            default -> throw new IllegalArgumentException("Unknown component contents kind " + kind);
        };
    }

    /**
     * Writes a registered contents as kind {@code 7}: the key, then the payload framed by its length. The
     * frame is what a peer without the class reads - the length says where the payload ends, which is all it
     * needs to step over it - and it cannot be written before the payload exists, so the payload is written
     * after one reserved byte and the frame is filled in behind it. A payload under 128 bytes costs that one
     * byte; a longer one moves over the bytes its VarInt still needs, a backward copy inside this one
     * buffer, so no scratch buffer is allocated on the encode path.
     */
    private void writeRegistered(FriendlyByteBuf buf, Registration registration, ComponentContents contents) {
        buf.writeByte(CONTENTS_REGISTERED);
        buf.writeUtf(registration.key());
        var frame = buf.writerIndex();
        buf.writeByte(0);
        var start = buf.writerIndex();
        registration.codec().encode(buf, contents);
        var length = buf.writerIndex() - start;
        var width = varIntSize(length);
        if (width > 1) {
            var end = buf.writerIndex();
            buf.ensureWritable(width - 1);
            for (var i = end - 1; i >= start; i--) {
                buf.setByte(i + width - 1, buf.getByte(i));
            }
            buf.writerIndex(end + width - 1);
        }
        writeVarIntAt(buf, frame, length);
    }

    /**
     * Reads kind {@code 7} back: the key, then the framed payload, decoded by the registration for that key -
     * or skipped whole when this side has none, which leaves the rest of the component alone. The decoded
     * object becomes the node through {@link MutableComponent#create(ComponentContents)}, which is the only
     * route that keeps it: the style and the siblings are then set on that node, so the contents is still the
     * object the codec built rather than a string that renders like it.
     */
    private MutableComponent readRegistered(FriendlyByteBuf buf) {
        var key = buf.readUtf();
        var length = buf.readVarInt();
        // the slice is exactly one frame, so whatever the codec reads - or forgets to read - the next node
        // starts where the writer said it does
        var payload = buf.readSlice(length);
        var registration = BY_KEY.get(key);
        if (registration == null) {
            reportMissing(key);
            return Component.empty();
        }
        var contents = registration.codec().decode(new FriendlyByteBuf(payload));
        if (contents == null) {
            throw new IllegalArgumentException("The codec registered for " + key + " decoded no contents");
        }
        return MutableComponent.create(contents);
    }

    /**
     * Names one key this side has no codec for, once: a server and a client with different mod sets are the
     * ordinary case, and one warning per key says so without one warning per component.
     */
    private void reportMissing(String key) {
        if (REPORTED.add(key)) {
            DataSyncLib.LOGGER.warn("No component contents codec is registered for {} on this side, so those"
                    + " nodes decode as empty; the mod that wrote them is not present here", key);
        }
    }

    /**
     * The bytes {@link VarInts#write} would spend on {@code value}. The frame needs the size before the bytes
     * exist, and {@link VarInts} only writes where the writer index is, so both halves of that are here.
     */
    private int varIntSize(int value) {
        var size = 1;
        var rest = value;
        while ((rest & ~0x7F) != 0) {
            size++;
            rest >>>= 7;
        }
        return size;
    }

    /**
     * {@link VarInts#write} at an index of its own: the frame's length is written after the payload it
     * describes, so it is patched in behind it rather than appended.
     */
    private void writeVarIntAt(FriendlyByteBuf buf, int index, int value) {
        var at = index;
        var rest = value;
        while ((rest & ~0x7F) != 0) {
            buf.setByte(at++, (rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        buf.setByte(at, rest);
    }

    /**
     * Writes one argument of a translatable. The args are only ever formatted into the translated text, and
     * formatting prints a {@code String} and a number the same way, so anything that is not a component (or
     * a list of them) travels as its text and comes back a {@code String}.
     */
    private void writeArgument(FriendlyByteBuf buf, Object argument) {
        if (argument instanceof Component component) {
            buf.writeByte(ARGUMENT_COMPONENT);
            write(buf, component);
            return;
        }
        if (argument instanceof List<?> list && isComponents(list)) {
            buf.writeByte(ARGUMENT_COMPONENTS);
            buf.writeVarInt(list.size());
            for (var element : list) {
                write(buf, (Component) element);
            }
            return;
        }
        buf.writeByte(ARGUMENT_TEXT);
        buf.writeUtf(String.valueOf(argument));
    }

    private Object readArgument(FriendlyByteBuf buf) {
        var kind = buf.readByte();
        if (kind == ARGUMENT_COMPONENT) {
            return read(buf);
        }
        if (kind == ARGUMENT_COMPONENTS) {
            var size = buf.readVarInt();
            List<Component> components = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                components.add(read(buf));
            }
            return components;
        }
        if (kind == ARGUMENT_TEXT) {
            return buf.readUtf();
        }
        throw new IllegalArgumentException("Unknown translatable argument kind " + kind);
    }

    /**
     * Builds a translatable from what was written: the fallback is the {@code String} overload when there
     * are no args, so an empty arg array never has to be allocated.
     */
    private MutableComponent readTranslatable(FriendlyByteBuf buf) {
        var key = buf.readUtf();
        var fallback = buf.readUtf();
        var count = buf.readVarInt();
        if (count == 0) {
            return fallback.isEmpty() ? Component.translatable(key) : Component.translatableWithFallback(key, fallback);
        }
        var args = new Object[count];
        for (int i = 0; i < count; i++) {
            args[i] = readArgument(buf);
        }
        return fallback.isEmpty()
                ? Component.translatable(key, args)
                : Component.translatableWithFallback(key, fallback, args);
    }

    private void writeDataSource(FriendlyByteBuf buf, DataSource source) {
        switch (source) {
            case BlockDataSource block -> {
                buf.writeByte(SOURCE_BLOCK);
                buf.writeUtf(block.posPattern());
            }
            case EntityDataSource entity -> {
                buf.writeByte(SOURCE_ENTITY);
                buf.writeUtf(entity.selectorPattern());
            }
            case StorageDataSource storage -> {
                buf.writeByte(SOURCE_STORAGE);
                buf.writeResourceLocation(storage.id());
            }
            case null, default -> throw new IllegalArgumentException("Unknown nbt data source " + source);
        }
    }

    private DataSource readDataSource(FriendlyByteBuf buf) {
        var kind = buf.readByte();
        return switch (kind) {
            case SOURCE_BLOCK -> new BlockDataSource(buf.readUtf());
            case SOURCE_ENTITY -> new EntityDataSource(buf.readUtf());
            case SOURCE_STORAGE -> new StorageDataSource(buf.readResourceLocation());
            default -> throw new IllegalArgumentException("Unknown nbt data source kind " + kind);
        };
    }

    private void writeOptional(FriendlyByteBuf buf, Optional<Component> component) {
        if (component.isPresent()) {
            buf.writeBoolean(true);
            write(buf, component.get());
            return;
        }
        buf.writeBoolean(false);
    }

    private Optional<Component> readOptional(FriendlyByteBuf buf) {
        return buf.readBoolean() ? Optional.of(read(buf)) : Optional.empty();
    }

    /**
     * The text this node itself renders, without its siblings - what an unknown contents can still say.
     * A contents whose own {@code visit} refuses to answer contributes nothing.
     */
    private String renderedText(ComponentContents contents) {
        return contents.visit((FormattedText.ContentConsumer<String>) Optional::of).orElse("");
    }

    /**
     * Kind 6's text back into a component. Empty text is {@link Component#empty()}, not an empty literal:
     * {@code ComponentContents.EMPTY} - the root of every {@code Component.empty().append(...)} tooltip -
     * has no kind of its own and travels this way, and it is also what the JSON form makes of
     * {@code {"text":""}}. An empty literal written as kind 0 is a different contents object and stays
     * one, so both keep the {@code equals} they had before the round trip.
     */
    private MutableComponent fromText(String text) {
        return text.isEmpty() ? Component.empty() : Component.literal(text);
    }

    private void writeStyle(FriendlyByteBuf buf, Style style) {
        var color = style.getColor();
        var insertion = style.getInsertion();
        var font = fontOf(style);
        var click = style.getClickEvent();
        var hover = style.getHoverEvent();
        int presence = color == null ? 0 : PRESENT_COLOR;
        if (insertion != null) presence |= PRESENT_INSERTION;
        if (font != null) presence |= PRESENT_FONT;
        if (click != null) presence |= PRESENT_CLICK;
        if (hover != null) presence |= PRESENT_HOVER;
        buf.writeByte(attributeBits(style));
        buf.writeByte(presence);
        if (color != null) buf.writeInt(color.getValue());
        if (insertion != null) buf.writeUtf(insertion);
        if (font != null) buf.writeResourceLocation(font);
        if (click != null) writeClick(buf, click);
        if (hover != null) writeHover(buf, hover);
    }

    /**
     * The style's font, or null where the field cannot be read: a font that cannot be told from the default
     * is written as no font at all, which is the same style to every getter there is. Only the writing side
     * needs this - a font that does arrive is read with {@link Style#withFont(ResourceLocation)}.
     */
    private ResourceLocation fontOf(Style style) {
        return RAW_ATTRIBUTES ? (ResourceLocation) FONT.get(style) : null;
    }

    private Style readStyle(FriendlyByteBuf buf) {
        int attributes = buf.readByte() & 0xFF;
        int presence = buf.readByte() & 0xFF;
        if (attributes == 0 && presence == 0) {
            return Style.EMPTY;
        }
        if (attributes >= ATTRIBUTE_STATES) {
            throw new IllegalArgumentException("Unknown style attribute byte " + attributes);
        }
        var style = Style.EMPTY
                .withBold(attribute(attributes, 81))
                .withItalic(attribute(attributes, 27))
                .withUnderlined(attribute(attributes, 9))
                .withStrikethrough(attribute(attributes, 3))
                .withObfuscated(attribute(attributes, 1));
        if ((presence & PRESENT_COLOR) != 0) style = style.withColor(TextColor.fromRgb(buf.readInt()));
        if ((presence & PRESENT_INSERTION) != 0) style = style.withInsertion(buf.readUtf());
        if ((presence & PRESENT_FONT) != 0) style = style.withFont(buf.readResourceLocation());
        if ((presence & PRESENT_CLICK) != 0) style = style.withClickEvent(readClick(buf));
        if ((presence & PRESENT_HOVER) != 0) style = style.withHoverEvent(readHover(buf));
        return style;
    }

    /**
     * The five attributes as one number: bold is the most significant digit, obfuscated the least. Two bits
     * per attribute would need ten, and the header has one byte for all five; the digits are what matters
     * on the wire, and {@code 3^5} of them fit a byte with room to spare.
     */
    private int attributeBits(Style style) {
        int packed = state(style, BOLD, style.isBold());
        packed = packed * 3 + state(style, ITALIC, style.isItalic());
        packed = packed * 3 + state(style, UNDERLINED, style.isUnderlined());
        packed = packed * 3 + state(style, STRIKETHROUGH, style.isStrikethrough());
        return packed * 3 + state(style, OBFUSCATED, style.isObfuscated());
    }

    /**
     * One attribute's digit: 0 absent, 1 explicit false, 2 true. Where the field could not be reached there
     * is no "explicit false" to tell from "absent", so the getter's answer is all there is and a flag that
     * is not true is written as absent - the reduced form {@link #RAW_ATTRIBUTES} describes.
     */
    private int state(Style style, VarHandle attribute, boolean value) {
        if (!RAW_ATTRIBUTES) {
            return value ? 2 : 0;
        }
        var held = attribute.get(style);
        return held == null ? 0 : (Boolean) held ? 2 : 1;
    }

    /**
     * One digit of the attribute byte back into the flag it stands for, {@code null} being "inherit". An
     * explicit false can only be put back where the field can be reached; without it the digit reads as
     * inherit, which is the reduced form the writer on that side produces anyway.
     */
    private Boolean attribute(int attributes, int place) {
        var digit = attributes / place % 3;
        if (digit == 2) return Boolean.TRUE;
        return digit == 1 && RAW_ATTRIBUTES ? Boolean.FALSE : null;
    }

    private void writeClick(FriendlyByteBuf buf, ClickEvent event) {
        buf.writeByte(event.getAction().ordinal());
        buf.writeUtf(event.getValue());
    }

    private ClickEvent readClick(FriendlyByteBuf buf) {
        var actions = ClickEvent.Action.values();
        var ordinal = buf.readByte();
        if (ordinal < 0 || ordinal >= actions.length) {
            throw new IllegalArgumentException("Unknown click event action " + ordinal);
        }
        return new ClickEvent(actions[ordinal], buf.readUtf());
    }

    /**
     * Writes a hover payload and the action it belongs to. The action is written from
     * {@link #HOVER_ACTIONS}; one this codec does not know, and an entity type without a registry key,
     * travel as the text hover of what they render, since a peer could not look either of them up. A value
     * whose type is not the one its action declares fails here, which is also what vanilla's own serializer
     * does with it.
     */
    private void writeHover(FriendlyByteBuf buf, HoverEvent hover) {
        var action = hover.getAction();
        if (action == HoverEvent.Action.SHOW_ITEM) {
            buf.writeByte(ACTION_ITEM);
            buf.writeByte(HOVER_ITEM);
            buf.writeItem(hover.getValue(HoverEvent.Action.SHOW_ITEM).getItemStack());
            return;
        }
        if (action == HoverEvent.Action.SHOW_ENTITY) {
            writeEntityHover(buf, hover.getValue(HoverEvent.Action.SHOW_ENTITY));
            return;
        }
        if (action == HoverEvent.Action.SHOW_TEXT) {
            writeTextHover(buf, hover.getValue(HoverEvent.Action.SHOW_TEXT));
            return;
        }
        writeTextHover(buf, Component.literal(hover.toString()));
    }

    private void writeEntityHover(FriendlyByteBuf buf, HoverEvent.EntityTooltipInfo info) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(info.type);
        if (key == null) {
            // a type with no key cannot be named on the wire: its name is what the peer can still be shown
            writeTextHover(buf, info.name == null ? Component.empty() : info.name);
            return;
        }
        buf.writeByte(ACTION_ENTITY);
        buf.writeByte(HOVER_ENTITY);
        buf.writeResourceLocation(key);
        buf.writeUUID(info.id);
        write(buf, info.name == null ? Component.empty() : info.name);
    }

    private void writeTextHover(FriendlyByteBuf buf, Component component) {
        buf.writeByte(ACTION_TEXT);
        buf.writeByte(HOVER_TEXT);
        write(buf, component);
    }

    /**
     * Reads a hover payload. The value kind decides how the payload is read and which hover is built, so a
     * payload whose kind disagrees with the action byte is still read as the value it is; the action byte is
     * only checked to be one this codec can name.
     */
    private HoverEvent readHover(FriendlyByteBuf buf) {
        checkHoverAction(buf.readByte());
        var kind = buf.readByte();
        return switch (kind) {
            case HOVER_TEXT -> new HoverEvent(HoverEvent.Action.SHOW_TEXT, read(buf));
            case HOVER_ITEM -> new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(buf.readItem()));
            case HOVER_ENTITY -> readEntityHover(buf);
            default -> throw new IllegalArgumentException("Unknown hover value kind " + kind);
        };
    }

    private HoverEvent readEntityHover(FriendlyByteBuf buf) {
        var key = buf.readResourceLocation();
        var id = buf.readUUID();
        var name = read(buf);
        // a type this session does not have cannot be looked up through a defaulted registry, so the key
        // itself is the question, not whether get(...) answered
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            return new HoverEvent(HoverEvent.Action.SHOW_TEXT, name);
        }
        return new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
                new HoverEvent.EntityTooltipInfo(BuiltInRegistries.ENTITY_TYPE.get(key), id, name));
    }

    /**
     * The action byte has to be one of the three the writer numbers; the payload that follows says how it is
     * read, so the reader never has to match the two up.
     */
    private void checkHoverAction(byte index) {
        if (index < 0 || index >= HOVER_ACTIONS.length) {
            throw new IllegalArgumentException("Unknown hover action " + index);
        }
    }

    /**
     * Whether a translatable argument is the list of components the wire has a kind for, rather than some
     * other list whose text is all that can be written.
     */
    private boolean isComponents(List<?> list) {
        for (var element : list) {
            if (!(element instanceof Component)) return false;
        }
        return true;
    }
}
