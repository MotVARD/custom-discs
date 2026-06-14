package com.motvard.customdiscs.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JukeboxTracker {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> tracked =
            new ConcurrentHashMap<>();

    public static void track(ResourceKey<Level> dim, BlockPos pos) {
        tracked.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    public static void untrack(ResourceKey<Level> dim, BlockPos pos) {
        tracked.computeIfPresent(dim, (k, set) -> {
            set.remove(pos);
            return set.isEmpty() ? null : set;
        });
    }

    public static Set<BlockPos> getAll(ResourceKey<Level> dim) {
        return tracked.getOrDefault(dim, Collections.emptySet());
    }

    /** Remove all tracked jukeboxes. Called when the server is stopping. */
    public static void clear() {
        tracked.clear();
    }
}
