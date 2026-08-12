/*
 * Meshelium — LGPL-3.0-only.
 */
package com.deds.meshelium.terrain;

/**
 * Wave-7 pure-CPU reordering of a section's translucent PREFIX (the first
 * {@code n} quads of its geometry stream, 64 bytes each — TERRAIN-DATA §4).
 *
 * <p><b>The model:</b> vanilla's TRANSLUCENT vertex buffer keeps its quads
 * in EMISSION order forever; only the index buffer is rebuilt when the
 * camera crosses a sort threshold ({@code MeshData.sortQuads} at build
 * time, {@code SortState.buildSortedIndexBuffer} on resort — both express
 * an order as a permutation of the ORIGINAL quad ids). Meshelium instead
 * stores the prefix quads physically in draw order, so a resort becomes a
 * byte permutation: slot {@code j} of the new prefix holds the quad
 * vanilla's new order names at position {@code j}.</p>
 *
 * <p>Callers track, per section, {@code currentOrder} — the original-quad-id
 * at each prefix slot (the build-time order the wave-3b decoder applied, or
 * the last applied resort). Given vanilla's {@code newOrder} (decoded from
 * the resorted index buffer, same id space), {@link #permute} rewrites the
 * prefix bytes so slot {@code j} holds original quad {@code newOrder[j]}.
 * Applying is idempotent-by-comparison: when {@code newOrder} equals
 * {@code currentOrder} the caller should skip (the spin-retry dedupe —
 * {@code ResortTransparencyTask.doTask} re-calls the upload with a fresh
 * {@code byteBuffer()} view per retry, bytecode ip 179-184, so identity
 * dedupe is impossible and content dedupe is the contract).</p>
 *
 * <p>Host-agnostic and allocation-explicit; no vanilla/LWJGL imports.</p>
 */
public final class TranslucentPrefix {

    private TranslucentPrefix() {}

    /**
     * Reorder {@code prefix} (length = {@code order.length * 64}) IN PLACE
     * from {@code currentOrder} to {@code newOrder}, using {@code scratch}
     * (same length as {@code prefix}) as the staging area.
     *
     * @throws IllegalArgumentException when the arrays disagree in size or
     *         either order is not a permutation of {@code 0..n-1} — callers
     *         count the section as malformed and keep the current order
     */
    public static void permute(byte[] prefix, int[] currentOrder, int[] newOrder, byte[] scratch) {
        int n = currentOrder.length;
        if (newOrder.length != n) {
            throw new IllegalArgumentException(
                    "order length mismatch: current " + n + " vs new " + newOrder.length);
        }
        if (prefix.length != n * TerrainVertexCodec.QUAD_STRIDE
                || scratch.length < prefix.length) {
            throw new IllegalArgumentException("prefix/scratch sized wrong for " + n + " quads");
        }
        // slotOfQuad[originalQuadId] = current prefix slot.
        int[] slotOfQuad = new int[n];
        java.util.Arrays.fill(slotOfQuad, -1);
        for (int slot = 0; slot < n; slot++) {
            int quad = currentOrder[slot];
            if (quad < 0 || quad >= n || slotOfQuad[quad] != -1) {
                throw new IllegalArgumentException("currentOrder is not a permutation of 0.." + (n - 1));
            }
            slotOfQuad[quad] = slot;
        }
        boolean[] seen = new boolean[n];
        for (int j = 0; j < n; j++) {
            int quad = newOrder[j];
            if (quad < 0 || quad >= n || seen[quad]) {
                throw new IllegalArgumentException("newOrder is not a permutation of 0.." + (n - 1));
            }
            seen[quad] = true;
            System.arraycopy(prefix, slotOfQuad[quad] * TerrainVertexCodec.QUAD_STRIDE,
                    scratch, j * TerrainVertexCodec.QUAD_STRIDE, TerrainVertexCodec.QUAD_STRIDE);
        }
        System.arraycopy(scratch, 0, prefix, 0, prefix.length);
    }
}
