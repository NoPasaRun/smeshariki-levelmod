package com.smeshariki.level.definition;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import net.minecraft.resources.Identifier;

/**
 * Событие прогона уровня: начало и конец уровня, задачи, кат-сцены, а также свои именованные события. В JSON — строка
 * {@code "вид"} или {@code "вид:предмет"}: {@code "level_started"}, {@code "task_completed:chop"},
 * {@code "cutscene_finished:levelmod:demo_intro"}, {@code "custom:door_opened"}. События механик других модов ({@code EXTERNAL}) пишутся
 * как есть, с пространством имён: {@code "gameplaymod:lava_wave_finished:a"}; уровень про них ничего не знает, только ждёт. Сравниваются по значению, поэтому прогон хранит
 * случившиеся события множеством, а {@link Trigger} ждёт их простой проверкой на вхождение.
 */
public record LevelEvent(Kind kind, String subject) {
	public enum Kind {
		LEVEL_STARTED, LEVEL_COMPLETED, LEVEL_ABANDONED, MISSION_STARTED, MISSION_COMPLETED, TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_RESET, CUTSCENE_STARTED, CUTSCENE_FINISHED, CUSTOM, MISSION_RESET, MISSION_RESUMED, CHECKPOINT_REACHED, CHECKPOINT_RESET, CHECKPOINT_RESUMED, EXTERNAL;

		private final String key = name().toLowerCase(Locale.ROOT);

		private boolean hasSubject() {
			return ordinal() >= MISSION_STARTED.ordinal() && this != EXTERNAL;
		}
	}

	public static final LevelEvent LEVEL_STARTED = new LevelEvent(Kind.LEVEL_STARTED, "");
	public static final LevelEvent LEVEL_COMPLETED = new LevelEvent(Kind.LEVEL_COMPLETED, "");
	public static final LevelEvent LEVEL_ABANDONED = new LevelEvent(Kind.LEVEL_ABANDONED, "");
	public static final Codec<LevelEvent> CODEC = Codec.STRING.comapFlatMap(LevelEvent::parse, LevelEvent::toString);

	public static LevelEvent missionStarted(String mission) {
		return new LevelEvent(Kind.MISSION_STARTED, mission);
	}

	/** Постройки миссии (поле {@code restore}) вернулись из снимка после сброса её задач: можно начать её механики заново (волны лавы). */
	public static LevelEvent missionReset(String mission) {
		return new LevelEvent(Kind.MISSION_RESET, mission);
	}

	/**
	 * Ожидание после сброса кончилось: все, кого вернули к началу задачи, отстояли отсчёт (поле миссии {@code reset_wait}); без ожидания событие приходит сразу
	 * после сброса. С него стоит начинать то, что бьёт по игроку (волны лавы, стрелы): раньше игрок стоит и не может уйти.
	 */
	public static LevelEvent missionResumed(String mission) {
		return new LevelEvent(Kind.MISSION_RESUMED, mission);
	}

	/** Игрок вошёл в чекпоинт миссии (не в первый: первый — начало миссии); {@code номер} — позиция в списке чекпоинтов миссии, с 1. Видно только в миссии. */
	public static LevelEvent checkpointReached(String mission, int number) {
		return new LevelEvent(Kind.CHECKPOINT_REACHED, mission + ":" + number);
	}

	/** Игрока вернули (откат) на чекпоинт миссии с этим номером. Видно только в миссии: на нём запускают механики после отката (волна лавы и т. п.). */
	public static LevelEvent checkpointReset(String mission, int number) {
		return new LevelEvent(Kind.CHECKPOINT_RESET, mission + ":" + number);
	}

	/**
	 * Ожидание после отката на чекпоинт кончилось (у чекпоинта своё время ожидания, {@code reset_wait}); без ожидания событие приходит сразу после отката. С него
	 * стоит запускать то, что бьёт по игроку, для конкретного чекпоинта: откат на второй чекпоинт не должен пускать волны лавы с первой.
	 */
	public static LevelEvent checkpointResumed(String mission, int number) {
		return new LevelEvent(Kind.CHECKPOINT_RESUMED, mission + ":" + number);
	}

	/** Миссия, которой принадлежит событие чекпоинта, или пусто. */
	public String checkpointMission() {
		int colon = subject.lastIndexOf(':');
		return (kind == Kind.CHECKPOINT_REACHED || kind == Kind.CHECKPOINT_RESET || kind == Kind.CHECKPOINT_RESUMED) && colon > 0 ? subject.substring(0, colon) : "";
	}

	public static LevelEvent missionCompleted(String mission) {
		return new LevelEvent(Kind.MISSION_COMPLETED, mission);
	}

	public static LevelEvent taskStarted(String task) {
		return new LevelEvent(Kind.TASK_STARTED, task);
	}

	public static LevelEvent taskCompleted(String task) {
		return new LevelEvent(Kind.TASK_COMPLETED, task);
	}

	public static LevelEvent taskFailed(String task) {
		return new LevelEvent(Kind.TASK_FAILED, task);
	}

	/** Задача начата заново: игрок погиб или вышел за её границу. */
	public static LevelEvent taskReset(String task) {
		return new LevelEvent(Kind.TASK_RESET, task);
	}

	public static LevelEvent cutsceneStarted(Identifier cutscene) {
		return new LevelEvent(Kind.CUTSCENE_STARTED, cutscene.toString());
	}

	public static LevelEvent cutsceneFinished(Identifier cutscene) {
		return new LevelEvent(Kind.CUTSCENE_FINISHED, cutscene.toString());
	}

	/** Событие механики другого мода: {@code "пространство:имя[:уточнение]"}. Может повторяться: ловить повторяемым триггером. */
	public static LevelEvent external(String event) {
		return new LevelEvent(Kind.EXTERNAL, event);
	}

	public static LevelEvent custom(String name) {
		return new LevelEvent(Kind.CUSTOM, name);
	}

	private static DataResult<LevelEvent> parse(String text) {
		int colon = text.indexOf(':');
		String key = colon < 0 ? text : text.substring(0, colon);
		String subject = colon < 0 ? "" : text.substring(colon + 1);
		for (Kind kind : Kind.values()) {
			if (kind == Kind.EXTERNAL || !kind.key.equals(key)) continue;
			if (kind.hasSubject() == subject.isEmpty()) return DataResult.error(() -> "Событие \"" + text + "\": " + (kind.hasSubject() ? "не указано, чего оно касается" : "лишнее уточнение"));
			return DataResult.success(new LevelEvent(kind, subject));
		}
		// чужое событие: пространство имён и имя, как у идентификатора
		int idEnd = colon < 0 ? -1 : text.indexOf(':', colon + 1);
		if (colon > 0 && Identifier.tryParse(idEnd < 0 ? text : text.substring(0, idEnd)) != null) return DataResult.success(external(text));
		return DataResult.error(() -> "Неизвестное событие: " + text);
	}

	@Override
	public String toString() {
		if (kind == Kind.EXTERNAL) return subject;
		return subject.isEmpty() ? kind.key : kind.key + ":" + subject;
	}
}
