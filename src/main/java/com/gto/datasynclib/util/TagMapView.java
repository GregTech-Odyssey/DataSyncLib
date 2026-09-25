package com.gto.datasynclib.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Live {@link Map} view over a {@link CompoundTag}'s <em>public</em> API, backing
 * {@link NbtUtil#COMPOUND_TAG_MAP}. It lets a {@link CompoundTag} field be managed as a
 * {@code Map<String, Tag>} for sync/persistence without touching NBT internals.
 *
 * <p>1.21 removed the access transformer, so the private {@code CompoundTag.tags} backing map is no
 * longer reachable; this view is built from {@code getAllKeys()}/{@code get}/{@code put}/{@code
 * remove}/{@code size()} instead. It is a <strong>live view</strong>: every read and write goes
 * straight through to the tag, so the conversion manager mutates the original tag in place rather
 * than a copy. {@link #put} and an entry's {@code setValue} reject {@code null} values, because a
 * {@link CompoundTag} cannot store them.</p>
 */
final class TagMapView extends AbstractMap<String, Tag> {

    private final CompoundTag tag;

    TagMapView(CompoundTag tag) {
        this.tag = tag;
    }

    @Override
    public Tag get(Object key) {
        return key instanceof String s ? tag.get(s) : null;
    }

    @Override
    public Tag put(String key, Tag value) {
        return tag.put(key, Objects.requireNonNull(value));
    }

    @Override
    public Tag remove(Object key) {
        var old = get(key);
        if (key instanceof String s) tag.remove(s);
        return old;
    }

    @Override
    public int size() {
        return tag.size();
    }

    @Override
    public Set<Entry<String, Tag>> entrySet() {
        return new AbstractSet<>() {

            @Override
            public int size() {
                return tag.size();
            }

            @Override
            public Iterator<Entry<String, Tag>> iterator() {
                var keys = tag.getAllKeys().iterator();
                return new Iterator<>() {

                    @Override
                    public boolean hasNext() {
                        return keys.hasNext();
                    }

                    @Override
                    public Entry<String, Tag> next() {
                        var key = keys.next();
                        return new SimpleEntry<>(key, tag.get(key)) {

                            @Override
                            public Tag setValue(Tag value) {
                                var old = tag.put(key, Objects.requireNonNull(value));
                                super.setValue(value);
                                return old;
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        keys.remove();
                    }
                };
            }
        };
    }
}
