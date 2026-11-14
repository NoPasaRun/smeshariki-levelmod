package com.smeshariki.level.run;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.definition.LevelEvent;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.nbt.CompoundTag;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Всё, что прогону нужно от сервера. Вынесено отдельно, чтобы логику прогона можно было проверять без запущенной игры. */
public interface RunHost {
	/** Позиция игрока, {@code null} — не в сети. */
	@Nullable Vec3 position(UUID player);

	/** Сколько подходящих предметов у игрока, вместе с одеждой персонажа. */
	int countItem(UUID player, java.util.function.Predicate<net.minecraft.world.item.ItemStack> match);

	void say(UUID player, Component message);

	/** Надеть на персонажа игрока одежду {@code item}, если её слот пуст и такого предмета у игрока нет. */
	void wear(UUID player, Identifier item);

	/** Кем сейчас играет, {@code null} — не вселён. */
	@Nullable Identifier character(UUID player);

	/** Отправить игрока в точку, когда он сможет туда попасть: погибший сначала возродится. */
	void returnTo(LevelRun run, UUID player, LevelRun.StartPoint point);

	/** Игрок {@code ticks} тиков стоит на месте и видит отсчёт сразу, не дожидаясь возврата (начало проверки миссии). По умолчанию как {@link #freeze}. */
	default void startWait(UUID player, int ticks) {
		freeze(player, ticks);
	}

	/** После возврата игрок {@code ticks} тиков стоит на месте и видит отсчёт (ожидание после сброса). */
	default void freeze(UUID player, int ticks) {
	}

	/**
	 * Игрок, закончивший миссию раньше других и возродившийся, стоит на месте ({@code on}), пока миссию не закончат все; {@code false} отпускает. Возврат на место
	 * ещё впереди, если погибший не возродился: ожидание начнётся после него. По умолчанию ничего.
	 */
	default void hold(UUID player, boolean on) {
	}

	/** Место на земле рядом с точкой, без ствола и воды; листва не считается, сквозь неё падают. */
	Vec3 groundAt(ResourceKey<Level> dimension, Vec3 near);

	/** Пол рядом с точкой на её высоте (подземная область: тоннель, пещера); не нашёлся — {@link #groundAt}. */
	Vec3 floorNear(ResourceKey<Level> dimension, Vec3 near);

	/** Измерение игрока в сети. */
	ResourceKey<Level> dimension(UUID player);

	int cutsceneDuration(Identifier cutscene);

	/** Показать кат-сцену участникам в сети (BehaviorMod); когда она кончится или её пропустят, прогон узнает через {@link LevelRun#cutsceneEnded}. */
	default void playCutscene(LevelRun run, Identifier cutscene) {
	}

	/** Игрок вне области задачи: сколько секунд осталось до провала. */
	void outsideArea(LevelRun run, UUID player, int secondsLeft);

	void backInArea(LevelRun run, UUID player);

	/** Проверка состояния игрока (зарегистрированная в мосте); вне сети — неверна. */
	boolean check(UUID player, Identifier condition, Mechanics.Params params);

	/** Выполнить действие механики другого мода для участников прогона в сети. */
	void runMechanic(LevelRun run, Identifier id, Mechanics.Params params);

	/**
	 * Снимок подвижных построек в области (всё, что механики умеют запоминать). {@code null} — чанки области ещё не загружены с сущностями: снимок
	 * был бы пустым (маркеры волн, плоты не найдены), и возвращать потом было бы нечего. Прогон пробует снова каждый тик.
	 */
	@org.jetbrains.annotations.Nullable CompoundTag saveSnapshot(TaskArea area);

	/** Прочитать в фоне версию мира для блоков области (в начале миссии). */
	void prepareBlocks(TaskArea area);

	/** Вернуть блоки области к текущей версии мира (понемногу, в следующие тики). */
	void resetBlocks(TaskArea area);

	/** Запомнить ячейки отката чекпоинта (блоки и сущности нужных видов в области) в NBT. */
	CompoundTag saveCells(TaskArea area, java.util.List<com.smeshariki.level.task.ResetCell> cells);

	/** Вернуть ячейки к запомненному (после того, как область вернулась к версии мира). */
	void restoreCells(TaskArea area, CompoundTag cells);

	/** Ожидание после возврата кончилось: механики продолжают то, что возврат придержал (волны лавы). */
	default void resumeSnapshots() {
	}

	/** Вернуть постройки к снимку. */
	void restoreSnapshot(ResourceKey<Level> dimension, CompoundTag snapshot);

	/** Сообщение участнику: в чат или над панелью. */
	void tell(UUID player, Component message, boolean overlay);

	/** Взять смешарика {@code character} в напарники ведущему (BehaviorMod): он приходит к ведущему; в мире его нет — появляется рядом. */
	void recruitCompanion(Identifier character, UUID leader);

	/** Отпустить напарника: остаётся, где стоит; не загружен — отпустится при загрузке как ничей. */
	void releaseCompanion(Identifier character);

	/** Приказ напарнику от ведущего; отказ («не держит стропу») показывается ведущему. */
	void orderCompanion(Identifier character, UUID leader, Identifier order, Mechanics.Params params);

	/** Событие случилось: разослать слушателям и игрокам. */
	void publish(LevelRun run, LevelEvent event);
}
