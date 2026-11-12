package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.level.task.ResetCell;
import com.smeshariki.ui.SelectScreen;
import com.smeshariki.ui.Ui;
import com.smeshariki.ui.UiScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Один чекпоинт миссии: восемь ячеек отката и действие при входе. В ячейку кладётся вид блока или сущности: при входе игрока в чекпоинт все такие в области миссии
 * запоминаются (сверх общего снимка подвижного), а откат на этот чекпоинт возвращает их. Действие при входе — механика с параметрами (как правило события);
 * событие «вход в чекпоинт» и «откат на чекпоинт» для других механик берётся в окне событий миссии.
 */
final class CheckpointScreen extends UiScreen {
	private static final int W = 440, COLUMNS = 4, CELL_H = 24, TOP = 48;

	private final LevelDraft.CheckpointDraft checkpoint;
	private final int number;

	CheckpointScreen(net.minecraft.client.gui.screens.Screen parent, LevelDraft.CheckpointDraft checkpoint, int number) {
		super(Component.translatable("gui.levelmod.checkpoint").append(" " + number), parent);
		this.checkpoint = checkpoint;
		this.number = number;
	}

	private static Component cellName(ResetCell cell) {
		if (cell.kind() == ResetCell.Kind.BLOCK) return BuiltInRegistries.BLOCK.getOptional(cell.type()).map(b -> b.getName()).orElse(Component.literal(cell.type().toString()));
		return BuiltInRegistries.ENTITY_TYPE.getOptional(cell.type()).map(t -> t.getDescription()).orElse(Component.literal(cell.type().toString()));
	}

	private static Component label(ResetCell cell) {
		return Component.translatable("gui.levelmod.checkpoint.cell." + (cell.kind() == ResetCell.Kind.BLOCK ? "block" : "entity"), cellName(cell));
	}

	private static Component mechanicName(Identifier id) {
		return MechanicRulesScreen.mechanicName(id);
	}

	@Override
	protected void init() {
		int w = panelWidth(W), x = centered(w), gap = 2;
		int cw = (w - (COLUMNS - 1) * gap) / COLUMNS;
		for (int i = 0; i < ResetCell.MAX; i++) {
			int index = i;
			ResetCell cell = i < checkpoint.resets.size() ? checkpoint.resets.get(i) : null;
			int cx = x + (i % COLUMNS) * (cw + gap), cy = TOP + (i / COLUMNS) * (CELL_H + gap);
			Component text = cell == null ? Component.translatable("gui.levelmod.checkpoint.cell.empty") : label(cell);
			// заполненные идут подряд с начала: пустая ячейка после последней заполненной добавляет новую
			boolean next = cell == null && i == checkpoint.resets.size();
			Button button = Button.builder(text, b -> chooseKind(index)).bounds(cx, cy, cw, CELL_H)
					.tooltip(Tooltip.create(cell == null ? Component.translatable("gui.levelmod.checkpoint.cell.tip") : Component.literal(cell.type().toString()))).build();
			button.active = cell != null || next;
			addRenderableWidget(button);
		}
		int y = TOP + 2 * (CELL_H + gap) + 18;
		int half = (w - 2) / 2;
		Component none = Component.translatable("gui.levelmod.checkpoint.enter.none");
		Component enter = checkpoint.enterRaw != null ? Component.translatable("gui.levelmod.checkpoint.enter.custom") : checkpoint.enterAction == null ? none : mechanicName(checkpoint.enterAction);
		Button action = Button.builder(Component.translatable("gui.levelmod.checkpoint.enter", enter), b -> {
			List<Identifier> all = new ArrayList<>();
			all.add(null);
			all.addAll(Mechanics.actions().keySet());
			all.addAll(Mechanics.toggles().keySet());
			SelectScreen.open(this, Component.translatable("gui.levelmod.select.action"), all, id -> id == null ? none : mechanicName(id).copy().append(Component.literal("  " + id).withStyle(net.minecraft.ChatFormatting.GRAY)),
					checkpoint.enterAction, id -> {
						checkpoint.enterAction = id;
						checkpoint.enterRaw = null;
					});
		}).bounds(x, y, half, Ui.BUTTON_H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.checkpoint.enter.tip"))).build();
		action.active = checkpoint.enterRaw == null;
		addRenderableWidget(action);
		if (checkpoint.enterAction != null) {
			addRenderableWidget(LevelEditorScreen.field(this, font, x + half + 2, y, half, "gui.levelmod.rules.args", checkpoint.enterArgs, 256, v -> checkpoint.enterArgs = v));
		}
		addBack(x + w / 4, height - 26, w / 2);
	}

	private void chooseKind(int index) {
		boolean filled = index < checkpoint.resets.size();
		List<String> kinds = new ArrayList<>(List.of("block", "entity"));
		if (filled) kinds.add("clear");
		SelectScreen.open(this, Component.translatable("gui.levelmod.checkpoint.cell.kind"), kinds, k -> Component.translatable("gui.levelmod.checkpoint.kind." + k), null, k -> {
			if (k.equals("clear")) {
				checkpoint.resets.remove(index);
				return;
			}
			ResetCell.Kind kind = k.equals("block") ? ResetCell.Kind.BLOCK : ResetCell.Kind.ENTITY;
			// выбор вида открывается после возврата в это окно: выбор из списка сам возвращает назад
			Minecraft.getInstance().execute(() -> chooseType(index, kind));
		});
	}

	private void chooseType(int index, ResetCell.Kind kind) {
		List<Identifier> types = new ArrayList<>();
		if (kind == ResetCell.Kind.BLOCK) {
			for (Identifier id : BuiltInRegistries.BLOCK.keySet()) if (!id.getPath().equals("air")) types.add(id);
		} else {
			for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) if (!id.getPath().equals("player")) types.add(id);
		}
		types.sort(Identifier::compareTo);
		Identifier current = index < checkpoint.resets.size() && checkpoint.resets.get(index).kind() == kind ? checkpoint.resets.get(index).type() : null;
		SelectScreen.open(this, Component.translatable("gui.levelmod.checkpoint.cell.pick." + (kind == ResetCell.Kind.BLOCK ? "block" : "entity")), types,
				id -> cellName(new ResetCell(kind, id)).copy().append(Component.literal("  " + id).withStyle(net.minecraft.ChatFormatting.GRAY)), current, id -> {
					ResetCell cell = new ResetCell(kind, id);
					if (index < checkpoint.resets.size()) checkpoint.resets.set(index, cell);
					else if (!checkpoint.resets.contains(cell)) checkpoint.resets.add(cell);
				});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, Component.translatable("gui.levelmod.checkpoint.hint"));
		int y = TOP + 2 * 26 + 6;
		g.centeredText(font, Component.translatable("gui.levelmod.checkpoint.enter.title"), width / 2, y, Ui.DIM);
	}
}
