package com.smeshariki.level.task;

import java.util.List;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Что задача видит в прогоне: кто играет и как отметить продвижение. Выполнена задача или нет, решает прогон по {@link Task#goal()}. */
public interface TaskTracker {
	/** Участники в сети. */
	List<UUID> players();

	Vec3 position(UUID player);

	boolean isInside(UUID player, TaskArea area);

	/** Сколько таких предметов у игрока прибавилось с начала задачи: оставшееся от прошлого прохождения не считается. */
	int gained(UUID player, Predicate<ItemStack> match);

	/** Верна ли проверка состояния для игрока в сети. */
	boolean check(UUID player, ConditionEntry condition);

	/** Память задачи об игроке (набор отметок): сохраняется в прогоне, сброс задачи её стирает. */
	int marks(UUID player);

	void setMarks(UUID player, int marks);

	/** Показать игроку текст. */
	void say(UUID player, Component message);

	/** Вклад игрока целиком: не прибавка, а текущее значение (сколько предметов сейчас в инвентаре). */
	void setCredit(UUID player, int amount);

	/** Вклад одного игрока. */
	void credit(UUID player, int amount);

	/** Продвижение сразу для всех (таймер). */
	void advance(int amount);
}
