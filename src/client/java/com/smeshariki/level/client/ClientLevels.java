package com.smeshariki.level.client;

import java.util.List;

import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.resources.Identifier;

/** Последний присланный сервером список уровней. */
final class ClientLevels {
	/** Новый объект при каждом обновлении: окно по нему понимает, что пора перестроиться. */
	static List<LevelList.LevelInfo> levels = List.of();
	/** Сколько тиков клиента прошло с последнего обновления: на него окно досчитывает время прогона. */
	static int age;
	static List<Identifier> cutscenes = List.of();
	static boolean canEdit, testing;

	/** Редактор доступен: хозяину и только в творческом режиме. В выживании окно уровней даёт одно — начать уровень. */
	static boolean editing() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		return canEdit && mc.player != null && mc.player.isCreative();
	}
	/** L возвращает в окно локаций: рисование области можно отменить туда же. */
	static boolean inLocations;
	/** Открыто окно локаций (в том числе из выбора места): все локации подсвечены в мире. */
	static boolean locationsOpen;
	static List<TaskArea> testAreas = List.of();
	static List<LevelList.TaskInfo> testTasks = List.of();
	static LevelList.Locations locations = LevelList.Locations.NONE;
	/** Область выбранной локации: её показывают искры. */
	static TaskArea shownLocation;
	/** Уровень в редакторе: клавиша L возвращает к нему, чтобы можно было сходить за углами области. */
	static LevelDraft draft;
	/** Выбранная в меню задача. */
	static TaskDraft draftTask;
	/** Выбранная в меню миссия: выбрана она, а не задача, если {@link #draftTask} пуст. */
	static LevelDraft.MissionDraft draftMission;
	/** L возвращает в меню задач, а не на страницу уровня. */
	static boolean inTaskMenu;

	private ClientLevels() {
	}

	static void set(LevelList list) {
		levels = list.levels();
		cutscenes = list.cutscenes();
		canEdit = list.canEdit();
		testing = list.testing();
		testAreas = list.testAreas();
		testTasks = list.testTasks();
		locations = list.locations();
		age = 0;
	}

	static void clear() {
		levels = List.of();
		cutscenes = List.of();
		canEdit = testing = inTaskMenu = inLocations = locationsOpen = false;
		testAreas = List.of();
		testTasks = List.of();
		locations = LevelList.Locations.NONE;
		shownLocation = null;
		draftMission = null;
		draft = null;
		draftTask = null;
	}
}
