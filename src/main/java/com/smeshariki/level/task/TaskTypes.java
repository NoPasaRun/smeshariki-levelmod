package com.smeshariki.level.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;

/**
 * Виды задач по значению {@code "type"} в JSON. Кодеки берутся только при вызове: {@link Task} с default-методами инициализируется
 * вместе с любой своей реализацией, и таблица в статическом поле зациклила бы инициализацию.
 */
final class TaskTypes {
	static final Codec<String> KEY_CODEC = Codec.STRING.validate(type -> codec(type) != null ? DataResult.success(type) : DataResult.error(() -> "Неизвестный вид задачи: " + type));

	private TaskTypes() {
	}

	static MapCodec<? extends Task> codec(String type) {
		return switch (type) {
			case ReachTask.TYPE -> ReachTask.MAP_CODEC;
			case ReachAreaTask.TYPE -> ReachAreaTask.MAP_CODEC;
			case GetItemTask.TYPE -> GetItemTask.MAP_CODEC;
			case TalkTask.TYPE -> TalkTask.MAP_CODEC;
			case MineBlockTask.TYPE -> MineBlockTask.MAP_CODEC;
			case WaitTask.TYPE -> WaitTask.MAP_CODEC;
			case ConditionTask.TYPE -> ConditionTask.MAP_CODEC;
			default -> null;
		};
	}
}
