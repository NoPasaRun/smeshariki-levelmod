package com.smeshariki.level.task;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/** Игровое действие участника, на которое может отозваться задача. Новая механика — новая запись и её источник в {@code LevelManager}. */
public sealed interface TaskSignal {
	/** Чьё это действие: задачи чужого персонажа на него не отзываются. */
	UUID player();

	record BlockMined(UUID player, Block block) implements TaskSignal {
	}

	/** Клик по свободному персонажу этого вида. */
	record TalkedTo(UUID player, Identifier character) implements TaskSignal {
	}
}
