package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.net.LevelLocationEdit;
import com.smeshariki.ui.CardList;
import com.smeshariki.ui.Ui;
import com.smeshariki.ui.UiScreen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Локации мира: именованные точки и области, на которые файлы уровней ссылаются как {@code "@имя"}. Слева сначала то, что уровни просят, а его нет,
 * потом уже отмеченное. Автор идёт на место, открывает окно, выбирает имя и ставит точку или рисует область.
 */
final class LocationsScreen extends UiScreen {
	private static final int MARGIN = 8, LIST_W = 150, CARD_H = 16, H = 20, ROW = 24, LINE = 11;
	private static final int WHITE = Ui.TEXT, GRAY = Ui.HINT, RED = Ui.BAD, YELLOW = Ui.ACCENT, GREEN = Ui.GOOD;

	private static String name = "";

	private final Screen parent;
	private LevelList.Locations shown;
	private final CardList cards = new CardList(CARD_H, 1);

	LocationsScreen() {
		this(null);
	}

	/** @param parent куда вернуться; {@code null} — в окно уровней */
	LocationsScreen(Screen parent) {
		super(Component.translatable("gui.levelmod.locations"));
		this.parent = parent;
		ClientLevels.inLocations = parent == null;
		ClientLevels.locationsOpen = true;
	}

	/** Имя, выбранное в окне сейчас: эту локацию подсветка рисует ярче. */
	static String currentName() {
		return name.trim();
	}

	/** Строка списка: имя и отмечена ли она. */
	private record Row(String name, LevelList.LocationInfo info) {
	}

	private List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		for (String missing : ClientLevels.locations.missing()) rows.add(new Row(missing, null));
		for (LevelList.LocationInfo info : ClientLevels.locations.marked()) rows.add(new Row(info.name(), info));
		return rows;
	}

	private LevelList.LocationInfo selected() {
		for (LevelList.LocationInfo info : ClientLevels.locations.marked()) if (info.name().equals(name.trim())) return info;
		return null;
	}

	private void send(Optional<net.minecraft.world.phys.Vec3> point, Optional<com.smeshariki.level.task.TaskArea> area) {
		ClientPlayNetworking.send(new LevelLocationEdit(name, point, area));
	}

	@Override
	protected void init() {
		cards.layout(MARGIN, 24, LIST_W, height - MARGIN);
		shown = ClientLevels.locations;
		LevelList.LocationInfo info = selected();
		ClientLevels.shownLocation = info == null ? null : info.area().orElse(null);
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN, half = (w - 2) / 2, y = 24;
		EditBox box = new EditBox(font, x, y, w, H, Component.translatable("gui.levelmod.locations.name"));
		box.setMaxLength(LevelLocationEdit.MAX_NAME);
		box.setHint(Component.translatable("gui.levelmod.locations.name"));
		box.setValue(name);
		box.setResponder(value -> name = value);
		addRenderableWidget(box);
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.locations.point"), b -> {
			if (!name.isBlank()) send(Optional.of(minecraft.player.position()), Optional.empty());
		}).bounds(x, y, half, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.locations.area"), b -> {
			if (name.isBlank()) return;
			LevelList.LocationInfo current = selected();
			AreaDrawing.draw(minecraft, current == null ? null : current.area().orElse(null), area -> send(Optional.empty(), Optional.of(area)), () -> minecraft.gui.setScreen(new LocationsScreen(parent)));
		}).bounds(x + half + 2, y, half, H).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.task.delete"), b -> {
			if (selected() != null) send(Optional.empty(), Optional.empty());
		}).bounds(x, y, half, H).build()).active = info != null;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.locations.new"), b -> {
			name = "";
			rebuildWidgets();
		}).bounds(x + half + 2, y, half, H).build());
		y += ROW;
		// откат области к сохранённой версии мира (только область: у точки нечего возвращать)
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.locations.reset"), b -> {
			LevelList.LocationInfo current = selected();
			if (current != null && current.area().isPresent()) ClientPlayNetworking.send(new com.smeshariki.level.net.LevelLocationReset(current.name()));
		}).bounds(x, y, w, H).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.levelmod.locations.reset.tip"))).build()).active = info != null && info.area().isPresent();
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.close"), b -> onClose()).bounds(x + half + 2, height - MARGIN - H, half, H).build());
	}

	@Override
	public void onClose() {
		ClientLevels.inLocations = false;
		ClientLevels.locationsOpen = false;
		ClientLevels.shownLocation = null;
		minecraft.gui.setScreen(parent != null ? parent : new LevelManagerScreen());
	}

	@Override
	public void tick() {
		super.tick();
		if (shown != ClientLevels.locations && !isEditing()) rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		List<Row> rows = rows();
		int row = cards.rowAt(event.x(), event.y(), rows.size());
		if (event.button() == 0 && row >= 0) {
			name = rows.get(row).name();
			rebuildWidgets();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		cards.wheel(x, scrollY, rows().size());
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, title, MARGIN, 9, WHITE);
		int top = 24, bottom = height - MARGIN;
		List<Row> rows = rows();
		cards.draw(g, rows.size(), mouseX, mouseY, (i, y, hover) -> {
			Row row = rows.get(i);
			cards.background(g, MARGIN, y, row.name().equals(name.trim()), hover);
			String mark = row.info() == null ? "! " : row.info().area().isPresent() ? "▭ " : "• ";
			g.text(font, firstLine(Component.literal(mark + row.name()), LIST_W - 16), MARGIN + 3, y + 3, row.info() == null ? YELLOW : GREEN);
			// цвет, которым эта локация подсвечена в мире
			if (row.info() != null) g.fill(MARGIN + LIST_W - 10, y + 3, MARGIN + LIST_W - 3, y + CARD_H - 4, 0xFF000000 | LocationsHighlight.colorOf(row.name()));
		});
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN, y = 24 + 3 * ROW + 2;
		LevelList.LocationInfo info = selected();
		if (info != null) {
			info.point().ifPresent(p -> g.text(font, String.format("%.1f %.1f %.1f", p.x, p.y, p.z), x, 24 + 2 * ROW + 6 + ROW, GRAY));
			y += LINE;
		}
		for (String problem : ClientLevels.locations.problems()) {
			for (FormattedCharSequence line : font.split(Component.literal(problem), w)) {
				if (y > height - MARGIN - H - LINE) return;
				g.text(font, line, x, y, RED);
				y += LINE;
			}
		}
	}

	private FormattedCharSequence firstLine(Component text, int width) {
		return Ui.firstLine(font, text, width);
	}
}
