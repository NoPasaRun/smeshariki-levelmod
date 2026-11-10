package com.smeshariki.level.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.level.task.MissionEntry;
import com.smeshariki.level.task.TaskEntry;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Уровень, файл {@code data/<мод>/level/<имя>.json}: миссии идут строго по порядку, в каждой одна или несколько задач. Пройден, когда выполнены
 * все миссии. Начало задачи задают {@code after} и {@code start_on} у неё самой или явные {@link Trigger}; задача, которую никто не запускает,
 * начинается вместе со своей миссией. После разбора остаются только триггеры, и прогон с анализатором работают с ними одними.
 *
 * @param characterStarts откуда начинает каждый персонаж: игрока переносит туда в начале уровня
 * @param characters за кого можно проходить уровень (типы сущностей), пустой список — за любого
 * @param startPos точка начала: откуда считается первый переход, а побочный уровень можно взять, только подойдя к ней
 * @param watches наблюдатели: условия, при которых случается своё событие ({@link Watch})
 */
public record LevelDefinition(LevelKind kind, Component title, Component description, LevelPreview preview, int minPlayers, int maxPlayers, int order, Optional<Vec3> startPos, List<MissionEntry> missions, List<Trigger> triggers, List<Identifier> characters, Map<Identifier, Vec3> characterStarts, List<Watch> watches) {
	/** На сколько блоков нужно подойти к началу побочного уровня, чтобы его взять. */
	public static final double TAKE_RADIUS = 8;
	/** Как записано автором, без разворота: так уровни из редактора хранятся в мире и ходят по сети. */
	public static final Codec<LevelDefinition> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
			LevelKind.CODEC.optionalFieldOf("kind", LevelKind.STORY).forGetter(LevelDefinition::kind),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(LevelDefinition::title),
			ComponentSerialization.CODEC.optionalFieldOf("description", Component.empty()).forGetter(LevelDefinition::description),
			LevelPreview.CODEC.optionalFieldOf("preview", LevelPreview.NONE).forGetter(LevelDefinition::preview),
			Codec.intRange(1, 64).optionalFieldOf("min_players", 1).forGetter(LevelDefinition::minPlayers),
			Codec.intRange(1, 64).optionalFieldOf("max_players", 1).forGetter(LevelDefinition::maxPlayers),
			Codec.INT.optionalFieldOf("order", 0).forGetter(LevelDefinition::order),
			Vec3.CODEC.optionalFieldOf("start_pos").forGetter(LevelDefinition::startPos),
			MissionEntry.CODEC.listOf().fieldOf("missions").forGetter(LevelDefinition::missions),
			Trigger.CODEC.listOf().optionalFieldOf("triggers", List.of()).forGetter(LevelDefinition::triggers),
			Identifier.CODEC.listOf().optionalFieldOf("characters", List.of()).forGetter(LevelDefinition::characters),
			Codec.unboundedMap(Identifier.CODEC, Vec3.CODEC).optionalFieldOf("character_starts", Map.of()).forGetter(LevelDefinition::characterStarts),
			Watch.CODEC.listOf().optionalFieldOf("watches", List.of()).forGetter(LevelDefinition::watches)
	).apply(i, LevelDefinition::new));
	/** Файлы датапаков: сразу с развёрнутыми триггерами. */
	public static final Codec<LevelDefinition> CODEC = RAW_CODEC.xmap(LevelDefinition::expanded, d -> d);

	/** Подходит ли персонаж этому уровню. */
	public boolean allowsCharacter(Identifier type) {
		return characters.isEmpty() || characters.contains(type);
	}

	/** Рядом ли игрок с точкой начала: по горизонтали, чтобы высота в файле уровня не требовала точности. */
	public static boolean nearStart(Vec3 start, Vec3 player) {
		double dx = start.x - player.x, dz = start.z - player.z;
		return dx * dx + dz * dz <= TAKE_RADIUS * TAKE_RADIUS;
	}

	/** Все задачи всех миссий по порядку. */
	public List<TaskEntry> tasks() {
		List<TaskEntry> all = new ArrayList<>();
		for (MissionEntry mission : missions) all.addAll(mission.tasks());
		return all;
	}

	public TaskEntry task(String id) {
		for (MissionEntry mission : missions) for (TaskEntry entry : mission.tasks()) if (entry.id().equals(id)) return entry;
		return null;
	}

	public MissionEntry mission(String id) {
		for (MissionEntry mission : missions) if (mission.id().equals(id)) return mission;
		return null;
	}

	/** Разворачивает сокращения в триггеры. Явные идут первыми, чтобы их номера не зависели от правок {@code after}. */
	public LevelDefinition expanded() {
		List<Trigger> all = new ArrayList<>(triggers);
		for (MissionEntry mission : missions) {
			for (TaskEntry entry : mission.tasks()) {
				LevelAction start = new LevelAction.StartTask(entry.id());
				List<LevelEvent> events = new ArrayList<>(entry.startOn());
				for (String task : entry.after()) events.add(LevelEvent.taskCompleted(task));
				if (!events.isEmpty()) all.add(new Trigger(events, entry.startDelay(), List.of(start)));
				else if (triggers.stream().noneMatch(t -> t.then().contains(start))) all.add(new Trigger(List.of(LevelEvent.missionStarted(mission.id())), entry.startDelay(), List.of(start)));
			}
		}
		return new LevelDefinition(kind, title, description, preview, minPlayers, maxPlayers, order, startPos, missions, List.copyOf(all), characters, characterStarts, watches);
	}
}
