package com.smeshariki.level.net;

import java.util.Optional;

import com.smeshariki.level.LevelMod;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Отметить локацию: точку или область; ни того ни другого — удалить. */
public record LevelLocationEdit(String name, Optional<Vec3> point, Optional<TaskArea> area) implements CustomPacketPayload {
	public static final int MAX_NAME = 32;
	public static final Type<LevelLocationEdit> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_location_edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelLocationEdit> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, LevelLocationEdit::name,
			ByteBufCodecs.optional(Vec3.STREAM_CODEC), LevelLocationEdit::point,
			ByteBufCodecs.optional(ByteBufCodecs.fromCodec(TaskArea.CODEC)), LevelLocationEdit::area,
			LevelLocationEdit::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
