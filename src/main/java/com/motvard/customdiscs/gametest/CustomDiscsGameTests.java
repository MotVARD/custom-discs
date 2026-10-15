package com.motvard.customdiscs.gametest;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.server.JukeboxTracker;
import com.motvard.customdiscs.server.JukeboxTicker;
import com.motvard.customdiscs.server.TrackDurations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
                // Destroy the jukebox the way an explosion would, with no BreakEvent.
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

    private static ItemStack disc(String fileName) {
        ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.DISC_DATA.get(), new CustomDiscData("server://" + fileName, "Test"));
        return disc;
    }

    /** A placeholder upload so the server treats the track as present (the audio is never decoded:
     *  tests that need a length record it in TrackDurations). */
    private static void ensureUpload(String fileName) {
        try {
            com.motvard.customdiscs.util.ModPaths.ensureDir(com.motvard.customdiscs.util.ModPaths.UPLOADS_DIR);
            java.nio.file.Path file = com.motvard.customdiscs.util.ModPaths.UPLOADS_DIR.resolve(fileName);
            if (!java.nio.file.Files.exists(file)) java.nio.file.Files.write(file, new byte[]{0});
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JukeboxBlockEntity placeJukeboxWithDisc(GameTestHelper helper, BlockPos rel, String fileName) {
        helper.setBlock(rel, Blocks.JUKEBOX);
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel)) instanceof JukeboxBlockEntity jukebox)) {
            throw new IllegalStateException("jukebox block entity was not created");
        }
        jukebox.setTheItem(disc(fileName));
        return jukebox;
    }

    /**
     * Issue #4: a jukebox playing a custom disc emits redstone power and comparator output like
     * a vanilla disc. Fails if the disc song is not in the jukebox_song registry.
     */
    @GameTest(template = "empty")
    public static void customDiscEmitsRedstone(GameTestHelper helper) {
        BlockPos rel = new BlockPos(2, 2, 2);
        BlockPos abs = helper.absolutePos(rel);
        ensureUpload("redstone_power.mp3");
        JukeboxBlockEntity jukebox = placeJukeboxWithDisc(helper, rel, "redstone_power.mp3");

        helper.assertTrue(jukebox.getSongPlayer().isPlaying(), "the jukebox song player should be playing");
        BlockState state = helper.getLevel().getBlockState(abs);
        helper.assertTrue(state.getSignal(helper.getLevel(), abs, Direction.UP) == 15, "a playing jukebox should emit power 15");
        helper.assertTrue(state.getAnalogOutputSignal(helper.getLevel(), abs) == 15, "comparator output should be 15");
        helper.succeed();
    }

    /**
     * The redstone signal ends when the real track ends, so hopper auto-jukeboxes advance, while
     * the comparator still sees the disc. The jukebox is never tracked by hand: placing it must
     * be enough for the ticker to pick it up.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void redstoneSignalEndsWithTrack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 2, 2);
        BlockPos abs = helper.absolutePos(rel);
        ensureUpload("one_second.mp3");
        TrackDurations.record("one_second.mp3", 1000L);
        JukeboxTicker.invalidate(level.dimension(), abs);

        helper.startSequence()
                .thenExecute(() -> placeJukeboxWithDisc(helper, rel, "one_second.mp3"))
                .thenExecute(() -> helper.assertTrue(
                        level.getBlockState(abs).getSignal(level, abs, Direction.UP) == 15,
                        "power should be on while the track plays"))
                .thenWaitUntil(() -> helper.assertTrue(
                        JukeboxTracker.getAll(level.dimension()).contains(abs),
                        "a newly placed jukebox should be tracked without a chunk reload"))
                .thenWaitUntil(() -> helper.assertTrue(
                        level.getBlockState(abs).getSignal(level, abs, Direction.UP) == 0,
                        "power should drop once the one-second track has ended"))
                .thenExecute(() -> {
                    if (level.getBlockEntity(abs) instanceof JukeboxBlockEntity jukebox) {
                        helper.assertFalse(jukebox.getSongPlayer().isPlaying(), "song player should be stopped");
                        helper.assertTrue(jukebox.getPersistentData().getBoolean("customdiscs_finished"),
                                "a finished disc should be marked so it does not restart after a reload");
                    }
                    helper.assertTrue(level.getBlockState(abs).getAnalogOutputSignal(level, abs) == 15,
                            "the comparator should still see the disc");
                })
                .thenSucceed();
    }

    /**
     * A disc whose track was deleted from the server plays nothing, so its jukebox must not hold
     * the redstone signal for the placeholder's full hour: it drops after about a second.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void deletedTrackReleasesRedstone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(2, 2, 2);
        BlockPos abs = helper.absolutePos(rel);
        TrackDurations.invalidate("deleted_track.mp3");
        JukeboxTicker.invalidate(level.dimension(), abs);

        helper.startSequence()
                .thenExecute(() -> placeJukeboxWithDisc(helper, rel, "deleted_track.mp3"))
                .thenWaitUntil(() -> helper.assertTrue(
                        level.getBlockState(abs).getSignal(level, abs, Direction.UP) == 0,
                        "power should drop for a deleted track"))
                .thenExecute(() -> {
                    if (level.getBlockEntity(abs) instanceof JukeboxBlockEntity jukebox) {
                        helper.assertTrue(jukebox.getSongPlayer().getTicksSinceSongStarted() == 0
                                        && jukebox.getPersistentData().getBoolean("customdiscs_finished"),
                                "the deleted track should be marked finished");
                    }
                })
                .thenSucceed();
    }
}
