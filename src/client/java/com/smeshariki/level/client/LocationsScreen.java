package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.net.LevelLocationEdit;

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
final class LocationsScreen extends Screen {
	private static final int MARGIN = 8, LIST_W = 150, CARD_H = 16, H = 20, ROW = 24, LINE = 11;
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, RED = 0xFFFF6060, YELLOW = 0xFFFFD040, GREEN = 0xFF60D060;
	private static final int CARD = 0x60000000, CARD_SELECTED = 0xA0304050;

	private static String name = "";

	private final Screen parent;
	private LevelList.Locations shown;
	private int listScroll;

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
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (shown != ClientLevels.locations) rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && event.x() >= MARGIN && event.x() < MARGIN + LIST_W && event.y() >= 24 && event.y() < height - MARGIN) {
			int row = ((int) event.y() - 24 + listScroll) / CARD_H;
			List<Row> rows = rows();
			if (row >= 0 && row < rows.size()) {
				name = rows.get(row).name();
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x < MARGIN + LIST_W) listScroll = Math.max(0, Math.min(listScroll - (int) Math.signum(scrollY) * CARD_H, Math.max(0, rows().size() * CARD_H - (height - MARGIN - 24))));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, title, MARGIN, 9, WHITE);
		int top = 24, bottom = height - MARGIN;
		g.enableScissor(MARGIN, top, MARGIN + LIST_W, bottom);
		List<Row> rows = rows();
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int y = top + i * CARD_H - listScroll;
			if (y + CARD_H <= top || y >= bottom) continue;
			g.fill(MARGIN, y, MARGIN + LIST_W, y + CARD_H - 1, row.name().equals(name.trim()) ? CARD_SELECTED : CARD);
			String mark = row.info() == null ? "! " : row.info().area().isPresent() ? "▭ " : "• ";
			g.text(font, firstLine(Component.literal(mark + row.name()), LIST_W - 16), MARGIN + 3, y + 3, row.info() == null ? YELLOW : GREEN);
			// цвет, которым эта локация подсвечена в мире
			if (row.info() != null) g.fill(MARGIN + LIST_W - 10, y + 3, MARGIN + LIST_W - 3, y + CARD_H - 4, 0xFF000000 | LocationsHighlight.colorOf(row.name()));
		}
		g.disableScissor();
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
		List<FormattedCharSequence> lines = font.split(text, Math.max(1, width));
		return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.getFirst();
	}
}
