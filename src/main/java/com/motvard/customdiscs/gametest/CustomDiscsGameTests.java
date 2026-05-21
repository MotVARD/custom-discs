package com.motvard.customdiscs.gametest;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.server.JukeboxTracker;
import com.motvard.customdiscs.server.JukeboxTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side integration tests. Run headless via the {@code runGameTestServer} task.
 * They are excluded from the published mod JAR by the {@code jar} task in build.gradle.
 */
@GameTestHolder(CustomDiscsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CustomDiscsGameTests {

    private CustomDiscsGameTests() {}

    /** JukeboxTracker registers and removes positions correctly. */
    @GameTest(template = "empty")
    public static void jukeboxTrackerRoundtrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));

        JukeboxTracker.untrack(level.dimension(), pos);
        helper.assertFalse(JukeboxTracker.getAll(level.dimension()).contains(pos),
                "position should start untracked");

        JukeboxTracker.track(level.dimension(), pos);
        helper.assertTrue(JukeboxTracker.getAll(level.dimension()).contains(pos),
                "track() should register the position");

        JukeboxTracker.untrack(level.dimension(), pos);
        helper.assertFalse(JukeboxTracker.getAll(level.dimension()).contains(pos),
                "untrack() should remove the position");

        helper.succeed();
    }

    /** The CustomDiscData component survives a write/read round-trip on an item stack. */
    @GameTest(template = "empty")
    public static void discDataComponentRoundtrip(GameTestHelper helper) {
        ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomDiscData data = new CustomDiscData("server://song.mp3", "My Song");
        disc.set(ModDataComponents.DISC_DATA.get(), data);

        CustomDiscData read = disc.get(ModDataComponents.DISC_DATA.get());
        helper.assertTrue(read != null, "disc data component should be present");
        helper.assertTrue(data.equals(read), "disc data should round-trip equal");
        helper.assertTrue("server://song.mp3".equals(read.source()), "source should be preserved");
        helper.assertTrue("My Song".equals(read.title()), "title should be preserved");

        helper.succeed();
    }

    /**
     * The headline guarantee: when a playing jukebox stops existing without a
     * BlockEvent.BreakEvent (explosion, piston, /setblock), the playback session
     * is still cleared and the position is untracked.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void jukeboxRemovalStopsPlayback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 2, 2);
        BlockPos abs = helper.absolutePos(rel);

        // Clear any state left by a previous run.
        JukeboxTicker.invalidate(level.dimension(), abs);
        JukeboxTracker.untrack(level.dimension(), abs);

        helper.startSequence()
                .thenExecute(() -> {
                    helper.setBlock(rel, Blocks.JUKEBOX);
                    if (level.getBlockEntity(abs) instanceof JukeboxBlockEntity jukebox) {
                        ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
                        disc.set(ModDataComponents.DISC_DATA.get(),
                                new CustomDiscData("server://test.mp3", "Test"));
                        jukebox.setTheItem(disc);
                    } else {
                        helper.fail("jukebox block entity was not created");
                    }
                    JukeboxTracker.track(level.dimension(), abs);
                })
                // The ticker (driven by LevelTickEvent) creates a session within ~10 ticks.
                .thenWaitUntil(() -> helper.assertTrue(
                        JukeboxTicker.hasSession(level.dimension(), abs),
                        "a playback session should exist after the disc was inserted"))
                // Destroy the jukebox the way an explosion would — no BreakEvent.
                .thenExecute(() -> helper.setBlock(rel, Blocks.AIR))
                // The guaranteed-stop logic must clear the session and untrack the spot.
                .thenWaitUntil(() -> {
                    helper.assertFalse(
                            JukeboxTicker.hasSession(level.dimension(), abs),
                            "session must be cleared after the jukebox was removed");
                    helper.assertFalse(
                            JukeboxTracker.getAll(level.dimension()).contains(abs),
                            "position must be untracked after the jukebox was removed");
                })
                .thenSucceed();
    }
}
