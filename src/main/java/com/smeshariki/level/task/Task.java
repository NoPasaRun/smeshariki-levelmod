package com.smeshariki.level.task;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.phys.Vec3;

/**
 * Поведение задачи: неизменяемое описание из JSON без состояния (прогресс хранит прогон). Новый вид задачи — запись с этим интерфейсом
 * и строка в {@link TaskTypes}. Оценка времени обязательна: из неё складывается ожидаемая длительность уровня.
 */
public interface Task {
	MapCodec<Task> CODEC = TaskTypes.KEY_CODEC.dispatchMap("type", Task::type, TaskTypes::codec);

	String type();

	/** Сколько единиц продвижения нужно: блоков, тиков. */
	int goal();

	/** @param from где игрок перед задачей, {@code null} — неизвестно */
	Estimate estimate(@Nullable Vec3 from);

	/** Где игрок окажется после задачи, {@code null} — там же, где был. */
	default @Nullable Vec3 endPosition() {
		return null;
	}

	/** Вызывается каждый тик, пока задача идёт. */
	default void tick(TaskTracker tracker) {
	}

	default void onSignal(TaskSignal signal, TaskTracker tracker) {
	}

	/** Подпись продвижения в окне уровней. */
	default String counter(int progress) {
		return goal() > 1 ? progress + "/" + goal() : "";
	}
}
