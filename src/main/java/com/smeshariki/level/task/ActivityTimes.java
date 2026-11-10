package com.smeshariki.level.task;

import net.minecraft.world.level.block.Block;

/** Сколько занимают действия при уверенной игре: основа оценок {@link Task#estimate}. Новая механика — новая величина здесь. */
public final class ActivityTimes {
	/** Вселённый персонаж на бегу: атрибут скорости 0,125 против 0,1 у игрока с его 4,317 блока/с. */
	public static final double WALK_BLOCKS_PER_TICK = 5.4 / 20;
	/** Путь, если неизвестно, откуда игрок идёт. */
	public static final double UNKNOWN_DISTANCE = 32;
	/** Добыть один предмет: как он получен, неизвестно, поэтому это грубая оценка. */
	public static final int GET_ITEM_TICKS = 100;
	/** Добиться состояния (залезть, взять стропу, нырнуть): грубая оценка, как именно, неизвестно. */
	public static final int CONDITION_TICKS = 200;
	/** Выслушать реплику. */
	public static final int TALK_TICKS = 60;
	/** Подойти к следующему блоку и прицелиться. */
	public static final int NEXT_BLOCK_TICKS = 20;
	/** Вселённый ломает блоки медленнее (GameplayMod): во столько раз. */
	private static final float POSSESSED_SLOWDOWN = 1f / com.smeshariki.gameplay.character.CharacterEntity.BREAK_SPEED_FACTOR;
	/** Скорость железного инструмента: считаем, что для блоков, которым нужен инструмент, он есть. */
	private static final float TOOL_SPEED = 6;

	private ActivityTimes() {
	}

	public static long walkTicks(double blocks) {
		return (long) Math.ceil(blocks / WALK_BLOCKS_PER_TICK);
	}

	/** Ломание блока вселённым: формула ванили (твёрдость × 30 / скорость инструмента, рукой скорость 1) с его замедлением. */
	public static long breakTicks(Block block) {
		float hardness = Math.max(0, block.defaultDestroyTime());
		return (long) Math.ceil(POSSESSED_SLOWDOWN * hardness * 30 / (block.defaultBlockState().requiresCorrectToolForDrops() ? TOOL_SPEED : 1));
	}
}
