package com.smeshariki.level.run;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Завершённые на 100% прогоны уровня в этом мире: из них считается средняя длительность. Брошенные сюда не попадают. */
public record LevelStats(long totalTicks, int runs) {
	public static final LevelStats EMPTY = new LevelStats(0, 0);
	static final Codec<LevelStats> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("total_ticks").forGetter(LevelStats::totalTicks),
			Codec.INT.fieldOf("runs").forGetter(LevelStats::runs)
	).apply(i, LevelStats::new));
	public static final StreamCodec<ByteBuf, LevelStats> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, LevelStats::totalTicks, ByteBufCodecs.VAR_INT, LevelStats::runs, LevelStats::new);

	LevelStats plus(long ticks) {
		return new LevelStats(totalTicks + ticks, runs + 1);
	}

	public long averageTicks() {
		return runs == 0 ? 0 : totalTicks / runs;
	}

	/** Тики как {@code м:сс} или {@code ч:мм:сс}. */
	public static String format(long ticks) {
		long seconds = ticks / 20;
		long hours = seconds / 3600;
		if (hours > 0) return String.format("%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}
}
