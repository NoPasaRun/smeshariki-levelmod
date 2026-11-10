package com.smeshariki.level.task;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.level.definition.LevelEvent;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * Задача в уровне: общее для всех видов (имя, подпись, режим в команде, зависимости) и само поведение.
 *
 * @param id имя внутри уровня, на него ссылаются события и триггеры
 * @param after задачи, после завершения которых эта начинается
 * @param startOn события, после которых эта начинается (вместе с {@code after}: нужны все)
 * @param startDelay через сколько тиков после них
 * @param character для какого персонажа задача (тип сущности); если им никто не играет, задача пропускается. Пусто — для всех
 * @param area где игрок должен оставаться, пока задача идёт: вышел и не вернулся — задача сбрасывается
 * @param optional поле {@code optional}: необязательная. Миссия кончается, не дожидаясь её; не выполненная к концу миссии закрывается (пропущена)
 */
public record TaskEntry(String id, Component title, TaskScope scope, Optional<Identifier> character, List<String> after, List<LevelEvent> startOn, int startDelay, Optional<TaskArea> area, boolean optional, Task task) {
	public static final Codec<TaskEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("id").forGetter(TaskEntry::id),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(TaskEntry::title),
			TaskScope.CODEC.optionalFieldOf("scope", TaskScope.TEAM).forGetter(TaskEntry::scope),
			Identifier.CODEC.optionalFieldOf("character").forGetter(TaskEntry::character),
			Codec.STRING.listOf().optionalFieldOf("after", List.of()).forGetter(TaskEntry::after),
			ExtraCodecs.compactListCodec(LevelEvent.CODEC).optionalFieldOf("start_on", List.of()).forGetter(TaskEntry::startOn),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("start_delay", 0).forGetter(TaskEntry::startDelay),
			TaskArea.CODEC.optionalFieldOf("area").forGetter(TaskEntry::area),
			Codec.BOOL.optionalFieldOf("optional", false).forGetter(TaskEntry::optional),
			Task.CODEC.forGetter(TaskEntry::task)
	).apply(i, TaskEntry::new));
}
