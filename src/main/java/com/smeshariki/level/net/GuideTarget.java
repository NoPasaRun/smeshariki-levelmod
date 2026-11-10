package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Куда игроку бежать (цель стрелки): {@code on} — цель есть, иначе подсказки нет. */
public record GuideTarget(boolean on, double x, double z) implements CustomPacketPayload {
	public static final Type<GuideTarget> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "guide_target"));
	public static final StreamCodec<ByteBuf, GuideTarget> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, GuideTarget::on, ByteBufCodecs.DOUBLE, GuideTarget::x, ByteBufCodecs.DOUBLE, GuideTarget::z, GuideTarget::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
