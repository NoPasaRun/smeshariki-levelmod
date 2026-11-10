package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Кнопка в окне уровней. Сервер сам проверяет, можно ли это сделать. */
public record LevelCommand(int action, Identifier level) implements CustomPacketPayload {
	/** Занять уровень и начать сбор группы (уровень на одного начинается сразу). */
	public static final int START = 0;
	public static final int JOIN = 1;
	/** Лидер запускает собранную группу. */
	public static final int BEGIN = 2;
	public static final int LEAVE = 3;
	/** Удалить уровень, созданный в редакторе. */
	public static final int DELETE = 4;
	/** Убрать правку из редактора у уровня из файла: снова работает файл из мода. */
	public static final int RESET = 5;

	public static final Type<LevelCommand> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelCommand> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, LevelCommand::action, Identifier.STREAM_CODEC, LevelCommand::level, LevelCommand::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
