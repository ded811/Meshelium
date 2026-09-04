/*
 * Copyright (C) 2026 Ded811
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.deds.meshelium.farfield.store;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

/**
 * The far-field disk store: shell records grouped 32x32 chunks per region
 * file under {@code <gamedir>/meshelium/farfield/<worldKey>/<dim>/}
 * (FAR-FIELD-DESIGN.md section 3.2: region-file style grouping so the
 * clear button and the size readout are cheap directory operations, and
 * partial invalidation is a region-local rewrite).
 *
 * <h2>File format: {@code r.<rx>.<rz>.mfr}</h2>
 * Our OWN simple format, deliberately not vanilla's RegionFile classes
 * (no sector padding, no shared code paths with the live world's IO):
 * <pre>
 *   bytes 0..4095     1024 big-endian int OFFSETS (absolute file offset
 *                     of the slot's record; 0 = absent)
 *   bytes 4096..8191  1024 big-endian int LENGTHS (record length in
 *                     bytes, tier prefix included)
 *   bytes 8192..      records, appended in write order:
 *                     [ u8 tier ][ ShellCodec record bytes ]
 * </pre>
 * Slot index = {@code (z & 31) * 32 + (x & 31)}. A rewrite appends the
 * new record and repoints the slot, so the old bytes become dead; when
 * dead bytes exceed half the data area (and a small floor, so tiny files
 * are not churned) the file is compacted through a temp-file rewrite and
 * an atomic-as-available move.
 *
 * <p>The tier byte is stored OUTSIDE the compressed record on purpose:
 * the truth-tier rule below needs the existing record's tier on every
 * write, and reading one byte at a known offset beats inflating the
 * whole record. The same tier also travels inside the ShellCodec header,
 * which is redundant by design - the store prefix is the enforcement
 * copy, the codec copy makes a record self-describing when it is read
 * back without store context.</p>
 *
 * <h2>Truth tiers, enforced here</h2>
 * FAR-FIELD-DESIGN.md section 3.2: visited ({@value ShellCodec#TIER_VISITED})
 * beats imported ({@value ShellCodec#TIER_IMPORTED}) beats generated
 * ({@value ShellCodec#TIER_GENERATED}). {@link #write(int, int, int, byte[])}
 * refuses (returns false, not an error) when the incoming tier is LOWER
 * than the stored record's: a visited extraction always overwrites a
 * generated record, a generated record never overwrites a visited one.
 * Equal tier overwrites - a revisit re-extracts and overwrites, which is
 * the staleness resolution (resolution 3, section 10).
 *
 * <h2>Threading: confined to the far-field IO thread</h2>
 * Every method must run on the single "meshelium-farfield-io" thread
 * (FARFIELD-CODEBASE-SEAM.md section 6.4: this store rides the mod's
 * FIRST owned thread; {@code FarField} is the only cross-thread surface).
 * The store pins the first calling thread and throws on any other, so a
 * future wave that reaches around the facade fails loudly in the suite
 * instead of corrupting a region header quietly. No internal locking -
 * confinement IS the synchronization.
 *
 * <h2>Failure accounting</h2>
 * Soft corruption (bad header, impossible slot entry) is repaired to
 * "absent" and counted on the injected error counter - the far field's
 * OWN counter, never the four wave-8 coverage-guard drop counters
 * (FARFIELD-CODEBASE-SEAM.md landmine L4: a cache bug must never turn
 * the mod passive). Hard IO failures propagate as IOException to the
 * caller ({@code FarField}'s task loop), which counts them the same way.
 */
public final class FarFieldStore {

    /** Chunks per region edge; 32x32 = 1024 slots, anvil-shaped. */
    private static final int REGION_CHUNKS = 32;
    private static final int SLOTS = REGION_CHUNKS * REGION_CHUNKS;
    /** 1024 offsets + 1024 lengths, 4 bytes each. */
    private static final int HEADER_BYTES = SLOTS * 8;
    /** Open region handles kept warm; writes cluster spatially. */
    private static final int MAX_OPEN_REGIONS = 4;
    /** Size readout cache lifetime (the readout is advisory UI data). */
    private static final long SIZE_CACHE_MILLIS = 5_000L;
    /** Offsets are ints, so a region file must stay under 2 GiB. */
    private static final long MAX_REGION_FILE_BYTES = Integer.MAX_VALUE;
    /** Do not bother compacting until this much is dead. */
    private static final long COMPACT_MIN_DEAD_BYTES = 64 * 1024;

    /** {@code <gamedir>/meshelium/farfield}, the delete-guard anchor. */
    private final Path farfieldBase;
    /** {@code farfieldBase/<worldKey>/<dim>} - this store's whole world. */
    private final Path root;
    private final LongAdder errorCounter;
    /** LRU of open regions (access order). IO-thread confined. */
    private final LinkedHashMap<Long, Region> openRegions =
            new LinkedHashMap<>(8, 0.75f, true);

    private Thread owner;
    private boolean closed;
    private long cachedSizeBytes;
    private long sizeCachedAtMillis;

    /**
     * @param gameDir the game directory ({@code FabricLoader.getGameDir()})
     * @param worldKey sanitized world key from {@code CacheKeys}
     * @param dimensionKey sanitized dimension key from {@code CacheKeys}
     * @param errorCounter the far field's own error counter
     *     ({@code FarField.farStoreErrors}); soft corruption increments it
     */
    public FarFieldStore(Path gameDir, String worldKey, String dimensionKey,
            LongAdder errorCounter) {
        this.errorCounter = errorCounter;
        requireSafeKey(worldKey, "worldKey");
        requireSafeKey(dimensionKey, "dimensionKey");
        this.farfieldBase = gameDir.toAbsolutePath().normalize()
                .resolve("meshelium").resolve("farfield");
        Path candidate = farfieldBase.resolve(worldKey).resolve(dimensionKey)
                .normalize();
        if (!candidate.startsWith(farfieldBase)) {
            // Unreachable with safe keys; the belt to sanitize's braces.
            throw new IllegalArgumentException(
                    "Far-field store path escapes meshelium/farfield: " + candidate);
        }
        this.root = candidate;
    }

    /**
     * Defensive twin of {@code CacheKeys.sanitize}: the store refuses any
     * key sanitize would not have produced, so no caller can smuggle a
     * path separator or a dot-traversal into the folder layout. Kept
     * local (not a CacheKeys call) so this class stays loadable in a
     * plain-JVM unit test without vanilla classes anywhere near the
     * classpath.
     */
    private static void requireSafeKey(String key, String what) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(what + " is null or empty");
        }
        boolean allDots = true;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                throw new IllegalArgumentException(
                        what + " contains unsanitized character: " + key);
            }
            allDots &= c == '.';
        }
        if (allDots) {
            throw new IllegalArgumentException(what + " is all dots: " + key);
        }
    }

    /** The folder this store reads and writes (for logs and the W4 UI). */
    public Path rootFolder() {
        return root;
    }

    // ------------------------------------------------------------------
    // Public API (IO thread only)
    // ------------------------------------------------------------------

    /**
     * Read the record for a chunk. Returns the ShellCodec record bytes
     * (tier prefix stripped), or null when absent. Never creates the
     * folder or the region file.
     */
    public byte[] read(int chunkX, int chunkZ) throws IOException {
        checkOwner();
        if (closed) {
            return null;
        }
        Region region = regionFor(chunkX, chunkZ, false);
        if (region == null) {
            return null;
        }
        return region.read(slotIndex(chunkX, chunkZ));
    }

    /**
     * Write a record at the given truth tier. Returns true when written;
     * false when the truth-tier rule refused it (an existing record of a
     * HIGHER tier stays - refusal is by-design behavior, not an error).
     * Creates the folder chain lazily, so a store that never writes never
     * touches the disk.
     */
    public boolean write(int chunkX, int chunkZ, int tier, byte[] record)
            throws IOException {
        checkOwner();
        if (closed) {
            return false;
        }
        if (tier < ShellCodec.TIER_GENERATED || tier > ShellCodec.TIER_VISITED) {
            throw new IllegalArgumentException("Unknown truth tier: " + tier);
        }
        if (record == null || record.length == 0) {
            throw new IllegalArgumentException("Empty record");
        }
        Region region = regionFor(chunkX, chunkZ, true);
        boolean written = region.write(slotIndex(chunkX, chunkZ), tier, record);
        if (written) {
            sizeCachedAtMillis = 0;
        }
        return written;
    }

    /**
     * Total bytes under this store's folder, from a directory walk cached
     * for {@value #SIZE_CACHE_MILLIS} ms (FAR-FIELD-DESIGN.md 3.2: the
     * readout is the folder's byte total, computed off the render thread,
     * cached). IO problems during the walk count an error and return the
     * last known value.
     *
     * <p><b>NOT what the W4 Far Terrain screen shows</b>, and the
     * difference is deliberate. This is ONE world and ONE dimension, and
     * it is IO-thread confined, so it can only be answered while a world
     * is joined with the master switch on. The screen has to work with
     * the far field off (that is exactly when a player goes looking for
     * disk space to reclaim) and it answers the question a player
     * actually asks, which is "how much is all of this costing me": it
     * calls {@code FarField.requestCacheSizeBytes}, which walks
     * {@code <gamedir>/meshelium/farfield} whole on its own short-lived
     * thread. This method stays for a future per-world readout.</p>
     */
    public long sizeBytes() {
        checkOwner();
        long now = System.currentTimeMillis();
        if (sizeCachedAtMillis != 0
                && now - sizeCachedAtMillis < SIZE_CACHE_MILLIS) {
            return cachedSizeBytes;
        }
        long total = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                total = files.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0;
                    }
                }).sum();
            } catch (IOException e) {
                errorCounter.increment();
                return Math.max(cachedSizeBytes, 0);
            }
        }
        cachedSizeBytes = total;
        sizeCachedAtMillis = now;
        return total;
    }

    /**
     * Delete everything under this store's folder. The guard is
     * absolute: every path deleted is re-verified to live inside
     * {@code meshelium/farfield}, and the method refuses to run on a
     * store whose root escaped it (the constructor already made that
     * impossible; defense in depth for anything that deletes). Only call
     * while a world is joined - {@code FarField} owns that guarantee,
     * since a store only exists between onWorldJoin and onWorldLeave.
     *
     * <p><b>NOT what the W4 delete button calls</b>, for the same reason
     * {@link #sizeBytes()} is not the screen's readout: the button
     * deletes the WHOLE cache, every world, and has to work with no
     * store open at all. That path is
     * {@code FarField.requestCacheClear}, which closes this store first
     * (an open region file is an open handle and Windows will not delete
     * one), wipes {@code <gamedir>/meshelium/farfield}, and then hands
     * the world a fresh store. This method stays for a future
     * clear-this-world-only action.</p>
     */
    public void clearAll() throws IOException {
        checkOwner();
        if (!root.startsWith(farfieldBase)) {
            throw new IOException(
                    "Refusing to clear a folder outside meshelium/farfield: " + root);
        }
        closeRegions();
        if (Files.isDirectory(root)) {
            List<Path> contents = new ArrayList<>();
            try (Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(root))
                        .forEach(contents::add);
            }
            for (Path p : contents) {
                if (!p.toAbsolutePath().normalize().startsWith(farfieldBase)) {
                    throw new IOException(
                            "Refusing to delete outside meshelium/farfield: " + p);
                }
                Files.deleteIfExists(p);
            }
        }
        cachedSizeBytes = 0;
        sizeCachedAtMillis = System.currentTimeMillis();
    }

    /**
     * Flush and close every open region. Idempotent; the store is
     * unusable afterwards (reads return null, writes return false).
     * Never throws - close runs on the world-leave path where an
     * exception could only lose MORE data; problems are counted instead.
     */
    public void close() {
        checkOwner();
        closed = true;
        closeRegions();
    }

    private void closeRegions() {
        for (Region region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                errorCounter.increment();
            }
        }
        openRegions.clear();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void checkOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException(
                    "FarFieldStore is confined to its IO thread (owner="
                            + owner.getName() + ", caller=" + current.getName() + ")");
        }
    }

    private static int slotIndex(int chunkX, int chunkZ) {
        return (chunkZ & (REGION_CHUNKS - 1)) * REGION_CHUNKS
                + (chunkX & (REGION_CHUNKS - 1));
    }

    private Region regionFor(int chunkX, int chunkZ, boolean create)
            throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        Region region = openRegions.get(key);
        if (region != null) {
            return region;
        }
        Path file = root.resolve("r." + regionX + "." + regionZ + ".mfr");
        if (!create && !Files.isRegularFile(file)) {
            return null;
        }
        if (create) {
            Files.createDirectories(root);
        }
        region = new Region(file);
        openRegions.put(key, region);
        while (openRegions.size() > MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<Long, Region>> eldest =
                    openRegions.entrySet().iterator();
            Region evicted = eldest.next().getValue();
            eldest.remove();
            try {
                evicted.close();
            } catch (IOException e) {
                errorCounter.increment();
            }
        }
        return region;
    }

    /**
     * One open {@code r.X.Z.mfr} file: the in-memory slot tables plus the
     * random-access handle. Header ints are persisted immediately on
     * every write (two 4-byte writes), so a crash loses at most the
     * record being appended, never the table.
     */
    private final class Region {

        private final Path file;
        private RandomAccessFile raf;
        private final int[] offsets = new int[SLOTS];
        private final int[] lengths = new int[SLOTS];
        private long liveBytes;

        Region(Path file) throws IOException {
            this.file = file;
            open();
        }

        private void open() throws IOException {
            raf = new RandomAccessFile(file.toFile(), "rw");
            long fileLength = raf.length();
            if (fileLength == 0) {
                writeEmptyHeader();
                return;
            }
            if (fileLength < HEADER_BYTES) {
                // Torn creation. Nothing recoverable; start clean.
                errorCounter.increment();
                raf.setLength(0);
                writeEmptyHeader();
                return;
            }
            byte[] header = new byte[HEADER_BYTES];
            raf.seek(0);
            raf.readFully(header);
            liveBytes = 0;
            for (int i = 0; i < SLOTS; i++) {
                int offset = readInt(header, i * 4);
                int length = readInt(header, SLOTS * 4 + i * 4);
                if (offset == 0 && length == 0) {
                    continue;
                }
                boolean sane = offset >= HEADER_BYTES && length >= 1
                        && (long) offset + length <= fileLength;
                if (!sane) {
                    // An impossible slot: repair to absent, count it.
                    errorCounter.increment();
                    offsets[i] = 0;
                    lengths[i] = 0;
                    continue;
                }
                offsets[i] = offset;
                lengths[i] = length;
                liveBytes += length;
            }
        }

        private void writeEmptyHeader() throws IOException {
            raf.seek(0);
            raf.write(new byte[HEADER_BYTES]);
            liveBytes = 0;
        }

        byte[] read(int slot) throws IOException {
            int offset = offsets[slot];
            if (offset == 0) {
                return null;
            }
            int length = lengths[slot];
            byte[] payload = new byte[length - 1];
            raf.seek(offset + 1L);
            raf.readFully(payload);
            return payload;
        }

        boolean write(int slot, int tier, byte[] record) throws IOException {
            if (offsets[slot] != 0) {
                int existingTier = tierAt(slot);
                if (existingTier > tier) {
                    return false;
                }
            }
            long end = raf.length();
            if (end + 1 + record.length > MAX_REGION_FILE_BYTES) {
                compact();
                end = raf.length();
                if (end + 1 + record.length > MAX_REGION_FILE_BYTES) {
                    throw new IOException("Region file full: " + file);
                }
            }
            raf.seek(end);
            raf.write(tier);
            raf.write(record);
            int oldLength = lengths[slot];
            offsets[slot] = (int) end;
            lengths[slot] = 1 + record.length;
            liveBytes += lengths[slot] - oldLength;
            raf.seek(slot * 4L);
            raf.writeInt(offsets[slot]);
            raf.seek(SLOTS * 4L + slot * 4L);
            raf.writeInt(lengths[slot]);
            long dead = deadBytes();
            if (dead >= COMPACT_MIN_DEAD_BYTES && dead * 2 > dataBytes()) {
                compact();
            }
            return true;
        }

        private int tierAt(int slot) throws IOException {
            raf.seek(offsets[slot]);
            int tier = raf.read();
            if (tier < 0) {
                // Table points past EOF - repair to absent, count it.
                errorCounter.increment();
                liveBytes -= lengths[slot];
                offsets[slot] = 0;
                lengths[slot] = 0;
                return -1;
            }
            return tier;
        }

        private long dataBytes() throws IOException {
            return Math.max(0, raf.length() - HEADER_BYTES);
        }

        private long deadBytes() throws IOException {
            return Math.max(0, dataBytes() - liveBytes);
        }

        /**
         * Rewrite the file with only live records: temp file beside the
         * real one, then a replace-move. The temp write happens before
         * the original is touched, so a crash mid-compaction leaves the
         * original intact (a stale .tmp is overwritten by the next
         * compaction of the same region).
         */
        private void compact() throws IOException {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            int[] newOffsets = new int[SLOTS];
            int[] newLengths = new int[SLOTS];
            long newLive = 0;
            try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw")) {
                out.setLength(0);
                out.seek(HEADER_BYTES);
                long cursor = HEADER_BYTES;
                for (int i = 0; i < SLOTS; i++) {
                    if (offsets[i] == 0) {
                        continue;
                    }
                    byte[] recordBytes = new byte[lengths[i]];
                    raf.seek(offsets[i]);
                    raf.readFully(recordBytes);
                    out.write(recordBytes);
                    newOffsets[i] = (int) cursor;
                    newLengths[i] = lengths[i];
                    cursor += lengths[i];
                    newLive += lengths[i];
                }
                byte[] header = new byte[HEADER_BYTES];
                for (int i = 0; i < SLOTS; i++) {
                    writeInt(header, i * 4, newOffsets[i]);
                    writeInt(header, SLOTS * 4 + i * 4, newLengths[i]);
                }
                out.seek(0);
                out.write(header);
            }
            raf.close();
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomicHere) {
                // Some filesystems refuse atomic replace; plain replace
                // is still safe because the temp file is complete.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            raf = new RandomAccessFile(file.toFile(), "rw");
            System.arraycopy(newOffsets, 0, offsets, 0, SLOTS);
            System.arraycopy(newLengths, 0, lengths, 0, SLOTS);
            liveBytes = newLive;
        }

        void close() throws IOException {
            raf.close();
        }
    }

    private static int readInt(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private static void writeInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }
}
