package com.smeshariki.level.client;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.smeshariki.ui.ListScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Области миссии ({@link LevelDraft.MissionDraft#areas}): при сбросе в каждой из них блоки возвращаются к версии мира, а подвижное и ячейки чекпоинтов — к
 * снимку. Областей может быть несколько (лавовое озеро и шар в разных местах); контур каждой берётся из локаций.
 */
final class MissionAreasScreen extends ListScreen {
	private static final int W = 360, ROW = 24, H = 20, TOP = 40;

	private final LevelDraft.MissionDraft mission;

	MissionAreasScreen(Screen parent, LevelDraft.MissionDraft mission) {
		super(Component.translatable("gui.levelmod.mission.areas"), parent);
		this.mission = mission;
	}

	private int visible() {
		return Math.max(1, (height - TOP - 34) / ROW);
	}

	@Override
	protected void init() {
		int w = listWidth(W), x = centered(w);
		List<String> areas = mission.areas;
		int visible = visible();
		bar.layout(x + w + 3, TOP, visible * ROW - 2, areas.size(), visible);
		for (int i = bar.scroll; i < Math.min(areas.size(), bar.scroll + visible); i++) {
			String name = areas.get(i);
			int y = TOP + (i - bar.scroll) * ROW;
			addRenderableWidget(Button.builder(Component.literal((i + 1) + ". " + name), b -> {
			}).bounds(x, y, w - 22, H).build()).active = false;
			addDelete(x + w - 20, y, () -> {
				areas.remove(name);
				rebuildWidgets();
			});
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.mission.areas.add"), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, true, name -> {
			if (name.isEmpty() || areas.contains(name)) return;
			areas.add(name);
			mission.areaRaw = null;
			bar.scroll = Math.max(0, areas.size() - visible());
		}))).bounds(x, bottom, w / 2 - 2, H).build());
		addBack(x + w / 2 + 2, bottom, w / 2 - 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, Component.translatable("gui.levelmod.mission.areas.hint"));
		if (mission.areas.isEmpty()) drawNote(g, Component.translatable("gui.levelmod.mission.areas.empty"), TOP + 6);
	}
}
