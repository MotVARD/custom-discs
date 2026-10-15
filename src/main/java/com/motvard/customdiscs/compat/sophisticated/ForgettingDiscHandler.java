package com.motvard.customdiscs.compat.sophisticated;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.p3pp3rf1y.sophisticatedcore.api.IDiscHandler;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wraps another mod's (or Sophisticated's own vanilla) disc handler so that starting one of its
 * discs in a storage tells {@link CustomDiscHandler} that the storage no longer plays a custom
 * disc. Otherwise a stale custom-disc record could later stop or re-time the other disc.
 */
final class ForgettingDiscHandler<I> implements IDiscHandler<I> {

    private final IDiscHandler<I> inner;
    private final Consumer<UUID> forget;

    ForgettingDiscHandler(IDiscHandler<I> inner, Consumer<UUID> forget) {
        this.inner = inner;
        this.forget = forget;
    }

    @Override
    public Optional<I> getSongInfo(ItemStack stack, Level level) {
        return inner.getSongInfo(stack, level);
    }

    @Override
    public void playDisc(ServerLevel level, BlockPos pos, UUID storageUuid, ItemStack disc, Runnable onFinished) {
        forget.accept(storageUuid);
        inner.playDisc(level, pos, storageUuid, disc, onFinished);
    }

    @Override
    public void playDisc(ServerLevel level, Vec3 pos, UUID storageUuid, ItemStack disc, int entityId, Runnable onFinished) {
        forget.accept(storageUuid);
        inner.playDisc(level, pos, storageUuid, disc, entityId, onFinished);
    }

    @Override
    public Optional<Integer> getMusicLengthInTicks(ItemStack stack, Level level) {
        return inner.getMusicLengthInTicks(stack, level);
    }

    @Override
    public boolean supports(ItemStack stack) {
        return inner.supports(stack);
    }

    @Override
    public Optional<ItemStack> getRandomDisc(RandomSource random) {
        return inner.getRandomDisc(random);
    }

    @Override
    public int getMusicDiscSize() {
        return inner.getMusicDiscSize();
    }
}
