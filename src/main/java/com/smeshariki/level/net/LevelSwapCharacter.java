package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок меняет персонажа на подходящего для уровня {@code level}: в выживании иначе не сменить. */
public record LevelSwapCharacter(Identifier level, Identifier character) implements CustomPacketPayload {
	public static final Type<LevelSwapCharacter> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_swap_character"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelSwapCharacter> CODEC = StreamCodec.composite(Identifier.STREAM_CODEC, LevelSwapCharacter::level, Identifier.STREAM_CODEC, LevelSwapCharacter::character, LevelSwapCharacter::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
