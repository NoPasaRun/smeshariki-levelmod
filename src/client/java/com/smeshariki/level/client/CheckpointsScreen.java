package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.SelectScreen;
import com.smeshariki.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Чекпоинты миссии ({@link LevelDraft.CheckpointDraft}) по приоритету: сверху первый (начало миссии), снизу последний (конец). Место берётся из локаций, а чекпоинт можно
 * привязать к одному персонажу (у каждого свой дом). Достигнутый чекпоинт важнее всех выше него: сброс возвращает на самый дальний из достигнутых.
 */
final class CheckpointsScreen extends ListScreen {
	private static final int W = 460, ROW = 24, H = 20, TOP = 44, WHO_W = 110, BUTTON_W = 20, GEAR_W = 20;

	private final LevelDraft level;
	private final LevelDraft.MissionDraft mission;

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
		int w = listWidth(W), x = centered(w);
		List<LevelDraft.CheckpointDraft> list = mission.checkpoints;
		int visible = visible();
		bar.layout(x + w + 3, TOP, visible * ROW - 2, list.size(), visible);
		for (int i = bar.scroll; i < Math.min(list.size(), bar.scroll + visible); i++) {
			int index = i;
			LevelDraft.CheckpointDraft checkpoint = list.get(i);
			int y = TOP + (i - bar.scroll) * ROW;
			int placeW = w - WHO_W - GEAR_W - 3 * BUTTON_W - 12;
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
			// ячейки отката и действие при входе
			boolean extra = !checkpoint.resets.isEmpty() || checkpoint.enterAction != null || checkpoint.enterRaw != null;
			addRenderableWidget(Button.builder(Component.literal(extra ? "§e⚙" : "⚙"), b -> minecraft.gui.setScreen(new CheckpointScreen(this, checkpoint, index + 1)))
					.bounds(x + placeW + WHO_W + 4, y, GEAR_W, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.checkpoints.settings"))).build());
			int bx = x + placeW + WHO_W + GEAR_W + 6;
			addMoveButtons(bx, y, i, list.size(), by -> move(index, by), Component.translatable("gui.levelmod.checkpoints.up"), Component.translatable("gui.levelmod.checkpoints.down"));
			addDelete(bx + MOVE_W + 1, y, () -> {
				list.remove(checkpoint);
				rebuildWidgets();
			});
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.checkpoints.add"), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, (Boolean) null, name -> {
			if (name.isEmpty()) return;
			LevelDraft.CheckpointDraft added = new LevelDraft.CheckpointDraft();
			added.location = name;
			mission.checkpoints.add(added);
			bar.scroll = Math.max(0, mission.checkpoints.size() - visible());
		}))).bounds(x, bottom, w / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> LevelEditorScreen.reopen(minecraft)).bounds(x + w / 2 + 2, bottom, w / 2 - 2, H).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, Component.translatable("gui.levelmod.checkpoints").append(": ").append(mission.title.isBlank() ? mission.id : mission.title), width / 2, 8, Ui.TEXT);
		g.centeredText(font, Component.translatable("gui.levelmod.checkpoints.order"), width / 2, 24, Ui.HINT);
		if (mission.checkpoints.isEmpty()) drawNote(g, Component.translatable("gui.levelmod.checkpoints.empty"), TOP + 6);
	}
}
