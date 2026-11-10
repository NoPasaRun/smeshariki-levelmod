package com.smeshariki.level.task;

/**
 * Оценка времени задачи в тиках.
 *
 * @param fixedTicks часть, которая не ускоряется от числа игроков (дойти, подождать)
 * @param sharedTicks работа, которую команда при {@link TaskScope#TEAM} делит между собой (добыть N блоков)
 */
public record Estimate(long fixedTicks, long sharedTicks) {
	public static Estimate fixed(long ticks) {
		return new Estimate(ticks, 0);
	}

	public static Estimate shared(long ticks) {
		return new Estimate(0, ticks);
	}

	public long total(TaskScope scope, int players) {
		return fixedTicks + (scope == TaskScope.TEAM ? sharedTicks / Math.max(1, players) : sharedTicks);
	}
}
