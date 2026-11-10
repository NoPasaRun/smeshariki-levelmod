package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.buildmod.client.OutlineDrawing;
import com.buildmod.shape.CustomShape;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Рисование границы инструментом своей формы из BuildMod: окно закрывается, Enter возвращает в него с готовой областью. */
final class AreaDrawing {
	private static final int CURVE_STEPS = 24;

	private AreaDrawing() {
	}

	/** @param existing что править, {@code null} — рисовать с нуля */
	static void draw(Minecraft mc, TaskArea existing, Consumer<TaskArea> apply, Runnable reopen) {
		List<BlockPos> points = existing == null ? List.of() : existing.points();
		List<int[]> edges = new ArrayList<>();
		if (existing != null) for (List<Integer> edge : existing.edges()) edges.add(edge.stream().mapToInt(Integer::intValue).toArray());
		mc.gui.setScreen(null);
		OutlineDrawing.start(points, edges, Component.translatable("message.levelmod.outline.hint").getString(), drawn -> {
			List<List<Integer>> graph = new ArrayList<>();
			for (int[] edge : drawn.edges()) graph.add(java.util.Arrays.stream(edge).boxed().toList());
			try {
				apply.accept(TaskArea.fromGraph(mc.level.dimension(), drawn.points(), graph, (a, b, bend, angle) -> CustomShape.curve(a, b, bend, angle, CURVE_STEPS)));
			} catch (TaskArea.InvalidOutline e) {
				return Component.translatable(e.translationKey).getString();
			}
			reopen.run();
			return null;
		});
	}
}
