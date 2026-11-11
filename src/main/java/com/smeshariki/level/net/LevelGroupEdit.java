package com.smeshariki.level.net;

import java.util.List;
import java.util.UUID;

import com.smeshariki.level.LevelMod;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Правка групп (реестр и состав): {@link Action#CREATE} заносит имя в реестр, {@link Action#DELETE} убирает имя и снимает группу со всех членов,
 * {@link Action#REFRESH} просит заново разослать список (числа членов), имя не нужно, {@link Action#ASSIGN} и {@link Action#REMOVE} ставят или снимают группу у выбранных объектов (сущности по UUID, их выбирают инструментом «Выбор» BuildMod).
 */
public record LevelGroupEdit(String name, Action action, List<UUID> objects) implements CustomPacketPayload {
	public static final int MAX_NAME = 32;

	public enum Action {
		CREATE, DELETE, ASSIGN, REMOVE, REFRESH
	}

	public static final Type<LevelGroupEdit> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_group_edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelGroupEdit> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, LevelGroupEdit::name,
			ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), LevelGroupEdit::action,
			UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(256)), LevelGroupEdit::objects,
			LevelGroupEdit::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
