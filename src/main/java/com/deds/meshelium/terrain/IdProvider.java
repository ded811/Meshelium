/*
 * Meshelium — LGPL-3.0-only.
 *
 * Ported from Nvidium by MCRcortex (LGPL-3.0).
 * Source: misc/reference/nvidium/src/main/java/me/cortex/nvidium/util/IdProvider.java
 * The Alphadium fork carries this file byte-identical modulo package rename.
 *
 * Algorithm identical to the original; only this header was added.
 */
package com.deds.meshelium.terrain;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;

/**
 * Dense id allocator with lowest-free-id reuse and tail compaction: released
 * ids form a sorted free set; {@link #provide()} hands out the lowest free id
 * (or grows the tail), and {@link #release(int)} shrinks the tail whenever the
 * highest outstanding ids become free — so {@link #maxIndex()} stays a tight
 * bound on the id range in use. Nvidium uses this for region ids
 * (NVIDIUM-ARCHITECTURE.md §2, RegionManager.java:34-35); Meshelium will too
 * in wave 3b.
 */
public class IdProvider {
    private int cid = 0;
    private final IntSortedSet free = new IntAVLTreeSet(Integer::compareTo);

    public int provide() {
        if (free.isEmpty()) {
            return cid++;
        }
        int ret = free.firstInt();
        free.remove(ret);
        return ret;
    }

    public void release(int id) {
        free.add(id);
        while ((!free.isEmpty()) && free.lastInt()+1 == cid) {
            free.remove(--cid);
        }
    }

    public int maxIndex() {
        return cid;
    }
}
