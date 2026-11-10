package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Чекпоинты миссии ({@link LevelDraft.CheckpointDraft}) по приоритету: сверху первый (начало миссии), снизу последний (конец). Место берётся из локаций, а чекпоинт можно
 * привязать к одному персонажу (у каждого свой дом). Достигнутый чекпоинт важнее всех выше него: сброс возвращает на самый дальний из достигнутых.
 */
final class CheckpointsScreen extends Screen {
	private static final int W = 460, ROW = 24, H = 20, TOP = 44, WHO_W = 120, BUTTON_W = 20;

	private final LevelDraft level;
	private final LevelDraft.MissionDraft mission;
	private int first;

	CheckpointsScreen(LevelDraft level, LevelDraft.MissionDraft mission) {
		super(Component.translatable("gui.levelmod.checkpoints"));
		this.level = level;
		this.mission = mission;
	}

	private int visible() {
		return Math.max(1, (height - TOP - 34) / ROW);
	}

	private static Component placeName(LevelDraft.CheckpointDraft checkpoint) {
		return checkpoint.location.isEmpty() ? Component.translatable("gui.levelmod.editor.none") : Component.literal(checkpoint.location);
	}

	private static Component characterName(Identifier character) {
		return character == null ? Component.translatable("gui.levelmod.checkpoints.everyone") : BuiltInRegistries.ENTITY_TYPE.getValue(character).getDescription();
	}

	private void move(int index, int by) {
		int target = index + by;
		if (target < 0 || target >= mission.checkpoints.size()) return;
		LevelDraft.CheckpointDraft moved = mission.checkpoints.remove(index);
		mission.checkpoints.add(target, moved);
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int w = Math.min(W, width - 8), x = (width - w) / 2;
		List<LevelDraft.CheckpointDraft> list = mission.checkpoints;
		first = Math.max(0, Math.min(first, list.size() - visible()));
		for (int i = first; i < Math.min(list.size(), first + visible()); i++) {
			int index = i;
			LevelDraft.CheckpointDraft checkpoint = list.get(i);
			int y = TOP + (i - first) * ROW;
			int placeW = w - WHO_W - 3 * BUTTON_W - 10;
			addRenderableWidget(Button.builder(Component.literal((i + 1) + ". ").append(placeName(checkpoint)), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, (Boolean) null, name -> {
				if (name.isEmpty()) return;
				checkpoint.location = name;
				checkpoint.rawPlace = null;
			}))).bounds(x, y, placeW, H).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.checkpoints.for", characterName(checkpoint.character)), b -> {
				List<Identifier> who = new ArrayList<>();
				who.add(null);
				who.addAll(level.boundCharacters());
				SelectScreen.open(this, Component.translatable("gui.levelmod.select.checkpoint_character"), who, CheckpointsScreen::characterName, checkpoint.character, c -> checkpoint.character = c);
			}).bounds(x + placeW + 2, y, WHO_W, H).build());
			int bx = x + placeW + WHO_W + 4;
			Button up = addRenderableWidget(Button.builder(Component.literal("↑"), b -> move(index, -1)).bounds(bx, y, BUTTON_W, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.checkpoints.up"))).build());
			up.active = i > 0;
			Button down = addRenderableWidget(Button.builder(Component.literal("↓"), b -> move(index, 1)).bounds(bx + BUTTON_W + 1, y, BUTTON_W, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.checkpoints.down"))).build());
			down.active = i < list.size() - 1;
			addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
				list.remove(checkpoint);
				rebuildWidgets();
			}).bounds(bx + 2 * BUTTON_W + 2, y, BUTTON_W, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.task.delete"))).build());
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.checkpoints.add"), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, (Boolean) null, name -> {
			if (name.isEmpty()) return;
			LevelDraft.CheckpointDraft added = new LevelDraft.CheckpointDraft();
			added.location = name;
			mission.checkpoints.add(added);
			first = Math.max(0, mission.checkpoints.size() - visible());
		}))).bounds(x, bottom, w / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> LevelEditorScreen.reopen(minecraft)).bounds(x + w / 2 + 2, bottom, w / 2 - 2, H).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int before = first;
		first = Math.max(0, Math.min(first - (int) Math.signum(scrollY), mission.checkpoints.size() - visible()));
		if (first != before) rebuildWidgets();
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, Component.translatable("gui.levelmod.checkpoints").append(": ").append(mission.title.isBlank() ? mission.id : mission.title), width / 2, 8, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("gui.levelmod.checkpoints.order"), width / 2, 24, 0xFFA0A0A0);
		if (mission.checkpoints.isEmpty()) g.centeredText(font, Component.translatable("gui.levelmod.checkpoints.empty"), width / 2, TOP + 6, 0xFFA0A0A0);
	}
}
