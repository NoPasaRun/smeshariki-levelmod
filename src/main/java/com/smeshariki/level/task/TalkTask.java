package com.smeshariki.level.task;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Поговорить с персонажем: клик по свободному персонажу этого вида. Настоящих диалогов пока нет, его реплика приходит в чат. */
public record TalkTask(Identifier character, String text) implements Task {
	static final String TYPE = "talk";
	static final MapCodec<TalkTask> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Identifier.CODEC.fieldOf("with").forGetter(TalkTask::character),
			Codec.STRING.optionalFieldOf("text", "").forGetter(TalkTask::text)
	).apply(i, TalkTask::new));

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public int goal() {
		return 1;
	}

	@Override
	public Estimate estimate(@Nullable Vec3 from) {
		return Estimate.fixed(ActivityTimes.walkTicks(ActivityTimes.UNKNOWN_DISTANCE) + ActivityTimes.TALK_TICKS);
	}

	@Override
	public void onSignal(TaskSignal signal, TaskTracker tracker) {
		if (!(signal instanceof TaskSignal.TalkedTo(var player, Identifier talkedTo)) || !talkedTo.equals(character)) return;
		if (!text.isEmpty()) tracker.say(player, com.smeshariki.level.definition.Speech.line(character, Component.literal(text)));
		tracker.credit(player, 1);
	}
}
