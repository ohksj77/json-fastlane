package io.jsonfastlane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class FieldOrderTracker {
    private static final int PACKED_BITS_PER_ID = 6;
    private static final int PACKED_MAX_ID = (1 << PACKED_BITS_PER_ID) - 1;
    private static final int PACKED_MAX_LENGTH = 10;

    private final JsonFastlaneOptions options;
    private final Map<FieldOrderKey, LongAdder> fieldOrders = new ConcurrentHashMap<>();
    private final LongAdder droppedFieldOrders = new LongAdder();
    private volatile FieldOrderCounter commonFieldOrder;

    FieldOrderTracker(JsonFastlaneOptions options) {
        this.options = options;
    }

    static boolean canPack(int length) {
        return length <= PACKED_MAX_LENGTH;
    }

    static long initialPackedOrder(int length) {
        return length;
    }

    static long appendPackedId(long packed, int index, int id) {
        if (id < 0 || id > PACKED_MAX_ID) {
            return -1;
        }
        return packed | ((long) id << packedShift(index));
    }

    static void copyPackedIds(long packed, int[] target) {
        int length = packedLength(packed);
        for (int i = 0; i < length; i++) {
            target[i] = packedIdAt(packed, i);
        }
    }

    void prime(int[] order) {
        FieldOrderKey key = new FieldOrderKey(order);
        LongAdder samples = samplesFor(key);
        if (samples != null && commonFieldOrder == null) {
            commonFieldOrder = new FieldOrderCounter(key, samples);
        }
    }

    void primePacked(long packedOrder) {
        FieldOrderKey key = new FieldOrderKey(packedOrder);
        LongAdder samples = samplesFor(key);
        if (samples != null && commonFieldOrder == null) {
            commonFieldOrder = new FieldOrderCounter(key, samples);
        }
    }

    void record(int[] order) {
        FieldOrderCounter common = commonFieldOrder;
        if (common != null && common.key().matches(order)) {
            common.samples().increment();
            return;
        }

        FieldOrderKey key = new FieldOrderKey(order);
        LongAdder samples = samplesFor(key);
        if (samples == null) {
            return;
        }
        samples.increment();

        FieldOrderCounter currentCommon = commonFieldOrder;
        if (currentCommon == null || samples.sum() > currentCommon.samples().sum()) {
            commonFieldOrder = new FieldOrderCounter(key, samples);
        }
    }

    void recordPacked(long packedOrder) {
        FieldOrderCounter common = commonFieldOrder;
        if (common != null && common.key().matchesPacked(packedOrder)) {
            common.samples().increment();
            return;
        }

        FieldOrderKey key = new FieldOrderKey(packedOrder);
        LongAdder samples = samplesFor(key);
        if (samples == null) {
            return;
        }
        samples.increment();

        FieldOrderCounter currentCommon = commonFieldOrder;
        if (currentCommon == null || samples.sum() > currentCommon.samples().sum()) {
            commonFieldOrder = new FieldOrderCounter(key, samples);
        }
    }

    List<FieldOrderSnapshot> snapshots(FieldDictionary dictionary) {
        List<FieldOrderSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<FieldOrderKey, LongAdder> entry : fieldOrders.entrySet()) {
            long samples = entry.getValue().sum();
            if (samples == 0) {
                continue;
            }
            snapshots.add(new FieldOrderSnapshot(
                entry.getKey().signature(dictionary),
                samples
            ));
        }
        snapshots.sort(Comparator.comparingLong(FieldOrderSnapshot::samples).reversed());
        return snapshots;
    }

    long droppedFieldOrders() {
        return droppedFieldOrders.sum();
    }

    private LongAdder samplesFor(FieldOrderKey key) {
        LongAdder samples = fieldOrders.get(key);
        if (samples != null) {
            return samples;
        }
        if (fieldOrders.size() >= options.maxRetainedFieldOrders()) {
            droppedFieldOrders.increment();
            return null;
        }
        return fieldOrders.computeIfAbsent(key, ignored -> new LongAdder());
    }

    private record FieldOrderCounter(FieldOrderKey key, LongAdder samples) {
    }

    private static final class FieldOrderKey {
        private final int[] ids;
        private final long packed;
        private final boolean packedKey;
        private final int hash;

        private FieldOrderKey(int[] ids) {
            long packedValue = tryPack(ids);
            if (packedValue >= 0) {
                this.ids = null;
                this.packed = packedValue;
                this.packedKey = true;
                this.hash = Long.hashCode(packedValue);
            } else {
                this.ids = Arrays.copyOf(ids, ids.length);
                this.packed = 0;
                this.packedKey = false;
                this.hash = Arrays.hashCode(this.ids);
            }
        }

        private FieldOrderKey(long packed) {
            this.ids = null;
            this.packed = packed;
            this.packedKey = true;
            this.hash = Long.hashCode(packed);
        }

        private String signature(FieldDictionary dictionary) {
            StringBuilder builder = new StringBuilder();
            int length = length();
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(dictionary.fieldName(idAt(i)));
            }
            return builder.toString();
        }

        private boolean matches(int[] otherIds) {
            if (packedKey) {
                return packed == tryPack(otherIds);
            }
            return Arrays.equals(ids, otherIds);
        }

        private boolean matchesPacked(long otherPacked) {
            return packedKey && packed == otherPacked;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldOrderKey key) || packedKey != key.packedKey) {
                return false;
            }
            return packedKey ? packed == key.packed : Arrays.equals(ids, key.ids);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        private int length() {
            return packedKey ? packedLength(packed) : ids.length;
        }

        private int idAt(int index) {
            if (!packedKey) {
                return ids[index];
            }
            return packedIdAt(packed, index);
        }

        private static long tryPack(int[] ids) {
            if (ids.length > PACKED_MAX_LENGTH) {
                return -1;
            }

            long packed = initialPackedOrder(ids.length);
            for (int i = 0; i < ids.length; i++) {
                packed = appendPackedId(packed, i, ids[i]);
                if (packed < 0) {
                    return -1;
                }
            }
            return packed;
        }
    }

    private static int packedLength(long packed) {
        return (int) (packed & 0xf);
    }

    private static int packedIdAt(long packed, int index) {
        return (int) ((packed >>> packedShift(index)) & PACKED_MAX_ID);
    }

    private static int packedShift(int index) {
        return 4 + index * PACKED_BITS_PER_ID;
    }
}
