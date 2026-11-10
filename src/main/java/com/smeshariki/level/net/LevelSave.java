package com.smeshariki.level.net;

import java.util.Optional;

import com.smeshariki.level.LevelMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Редактор сохраняет уровень: новый, если {@code id} пуст. JSON как в файлах уровней, локации в нём по имени. Сервер отвечает {@link LevelSaveResult}. */
public record LevelSave(Optional<Identifier> id, String json) implements CustomPacketPayload {
	public static final Type<LevelSave> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_save"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelSave> CODEC = StreamCodec.composite(
			ByteBufCodecs.optional(Identifier.STREAM_CODEC), LevelSave::id,
			ByteBufCodecs.stringUtf8(LevelList.MAX_JSON), LevelSave::json,
			LevelSave::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
