package de.boondocksulfur.christmas.manager;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snow layers and ice the weather forms while the event is active. The restore removes exactly
 * these blocks, also where something was built on top of them later (snow then forms again on
 * the new roof, so a column can hold several positions).
 *
 * <p>Positions are collected in memory on the chunk's thread and written to the snapshot
 * database in batches ({@link #flush}). Each position is packed into one int
 * ({@link #pack}); a chunk record is the sorted set of its positions.
 */
public final class FormedBlocks {

    private static final class Entry {
        final Set<Integer> positions = new HashSet<>();
        boolean retired;
    }

    private record Key(String world, int x, int z) {
    }

    private final Map<Key, Entry> pending = new ConcurrentHashMap<>();

    /** Packs a position inside its chunk: bit 24 = ice, bits 16-23 = column (x * 16 + z), bits 0-15 = Y. */
    public static int pack(int blockX, int blockY, int blockZ, boolean ice) {
        return (ice ? 1 << 24 : 0) | (((blockX & 15) * 16 + (blockZ & 15)) << 16) | (blockY & 0xFFFF);
    }

    public static boolean isIce(int packed) {
        return (packed & (1 << 24)) != 0;
    }

    public static int localX(int packed) {
        return (packed >> 20) & 15;
    }

    public static int localZ(int packed) {
        return (packed >> 16) & 15;
    }

    public static int y(int packed) {
        return (short) (packed & 0xFFFF);
    }

    /** Remembers a formed snow layer ({@code ice == false}) or ice block at a block position. */
    public void record(String world, int blockX, int blockY, int blockZ, boolean ice) {
        Key key = new Key(world, blockX >> 4, blockZ >> 4);
        int packed = pack(blockX, blockY, blockZ, ice);
        while (true) {
            Entry entry = pending.computeIfAbsent(key, k -> new Entry());
            synchronized (entry) {
                // Taken by a flush in the meantime: start a fresh entry
                if (entry.retired) continue;
                entry.positions.add(packed);
                return;
            }
        }
    }

    /** Writes all collected positions into the database and forgets them in memory. */
    public void flush(BiomeSnapshotDatabase database) throws SQLException {
        if (database == null || !database.isOpen()) return;
        for (Map.Entry<Key, Entry> e : pending.entrySet()) {
            Entry entry = e.getValue();
            int[] positions;
            synchronized (entry) {
                entry.retired = true;
                positions = toSortedArray(entry.positions);
            }
            pending.remove(e.getKey(), entry);
            if (positions.length == 0) continue;
            try {
                database.mergeFormed(e.getKey().world(), e.getKey().x(), e.getKey().z(), positions);
            } catch (SQLException ex) {
                putBack(e.getKey(), positions);
                throw ex;
            }
        }
    }

    /** Returns positions of a failed write to memory. */
    private void putBack(Key key, int[] positions) {
        while (true) {
            Entry entry = pending.computeIfAbsent(key, k -> new Entry());
            synchronized (entry) {
                if (entry.retired) continue;
                for (int p : positions) entry.positions.add(p);
                return;
            }
        }
    }

    /** Drops everything collected but not yet written. */
    public void clear() {
        pending.clear();
    }

    /** @return the sorted union of two position sets */
    static int[] union(int[] a, int[] b) {
        int[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return Arrays.stream(all).distinct().sorted().toArray();
    }

    private static int[] toSortedArray(Set<Integer> set) {
        return set.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    static byte[] encode(int[] positions) {
        ByteBuffer buffer = ByteBuffer.allocate(positions.length * 4);
        for (int p : positions) buffer.putInt(p);
        return buffer.array();
    }

    static int[] decode(byte[] data) {
        if (data == null) return new int[0];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int[] positions = new int[data.length / 4];
        for (int i = 0; i < positions.length; i++) positions[i] = buffer.getInt();
        return positions;
    }
}
