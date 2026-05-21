package com.motvard.customdiscs.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record CustomDiscData(String source, String title) {

    public static final Codec<CustomDiscData> CODEC = RecordCodecBuilder.create(inst ->
            inst.group(
                    Codec.STRING.fieldOf("source").forGetter(CustomDiscData::source),
                    Codec.STRING.fieldOf("title").forGetter(CustomDiscData::title)
            ).apply(inst, CustomDiscData::new)
    );

    public static final StreamCodec<ByteBuf, CustomDiscData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CustomDiscData::source,
            ByteBufCodecs.STRING_UTF8, CustomDiscData::title,
            CustomDiscData::new
    );
}
