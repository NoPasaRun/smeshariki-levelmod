package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.smeshariki.level.net.LevelList;
import com.smeshariki.ui.ListScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Выбор локации для задачи или старта: список имён нужного вида и «нет». Своих локаций можно добавить тут же, через окно локаций. */
final class LocationPickerScreen extends ListScreen {
	private static final int W = 240, H = 20, ROW = 22;

	/** Нужны области, точки или ({@code null}) то и другое. */
	private final Boolean areas;
	private final Consumer<String> apply;

	/** @param areas нужны области; иначе точки */
	LocationPickerScreen(Screen parent, boolean areas, Consumer<String> apply) {
		this(parent, Boolean.valueOf(areas), apply);
	}

	/** @param areas нужны области, точки или ({@code null}) любое место */
	LocationPickerScreen(Screen parent, Boolean areas, Consumer<String> apply) {
		super(Component.translatable(areas == null ? "gui.levelmod.locations.pick_place" : areas ? "gui.levelmod.locations.pick_area" : "gui.levelmod.locations.pick_point"), parent);
		this.areas = areas;
		this.apply = apply;
	}

	private List<String> names() {
		List<String> names = new ArrayList<>();
		for (LevelList.LocationInfo info : ClientLevels.locations.marked()) if (areas == null || (areas ? info.area().isPresent() : info.point().isPresent())) names.add(info.name());
		return names;
	}

	private void choose(String name) {
		apply.accept(name);
		minecraft.gui.setScreen(parent);
	}

	@Override
	protected void init() {
		int x = (width - W) / 2, y = 24;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.none"), b -> choose("")).bounds(x, y, W, H).build());
		y += ROW;
		List<String> names = names();
		int visible = rowsFit(y, 54, ROW);
		bar.layout(x + W + 3, y, visible * ROW - 2, names.size(), visible);
		for (int i = 0; i < visible && bar.scroll + i < names.size(); i++) {
			String name = names.get(bar.scroll + i);
			addRenderableWidget(Button.builder(Component.literal(name), b -> choose(name)).bounds(x, y + i * ROW, W, H).build());
		}
		int half = W / 2 - 1, bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.locations"), b -> minecraft.gui.setScreen(new LocationsScreen(this))).bounds(x, bottom, half, H).build());
		addBack(x + W - half, bottom, half);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, null);
	}
}
