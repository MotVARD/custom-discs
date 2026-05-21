package com.motvard.customdiscs.item;

import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class CustomMusicDiscItem extends Item {

    public CustomMusicDiscItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomDiscData data = stack.get(ModDataComponents.DISC_DATA.get());
        if (data != null) {
            tooltipComponents.add(Component.literal("♪ " + data.title())
                    .withStyle(style -> style.withColor(0xFFD700)));

            if (tooltipFlag.isAdvanced()) {
                tooltipComponents.add(Component.literal(data.source())
                        .withStyle(style -> style.withColor(0x888888)));
            } else {
                tooltipComponents.add(Component.translatable("item.customdiscs.custom_music_disc.hint")
                        .withStyle(style -> style.withColor(0x555555)));
            }
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public Component getName(ItemStack stack) {
        CustomDiscData data = stack.get(ModDataComponents.DISC_DATA.get());
        if (data != null) {
            return Component.translatable("item.customdiscs.custom_music_disc")
                    .append(Component.literal(" - " + data.title())
                            .withStyle(style -> style.withColor(0xFFD700)));
        }
        return super.getName(stack);
    }
}
