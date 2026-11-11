package com.smeshariki.level.client;

import java.util.List;

import com.smeshariki.level.net.LevelGroupEdit;
import com.smeshariki.level.net.LevelList;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Реестр групп объектов: слева группы с числом членов (среди загруженных), справа имя, «Создать», «Добавить в область» и «Убрать из области»
 * (область рисуется инструментом BuildMod, как у локаций; в неё попадают объекты любого вида, которым можно задать группу, например шары и маркеры),
 * «Удалить» (снимает группу и со всех членов). Группу на объект файлы уровней и механики ссылаются по имени, параметром {@code group}.
 */
final class GroupsScreen extends Screen {
	private static final int MARGIN = 8, LIST_W = 150, CARD_H = 16, H = 20, ROW = 24, LINE = 11;
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, GREEN = 0xFF60D060, YELLOW = 0xFFFFD040;
	private static final int CARD = 0x60000000, CARD_SELECTED = 0xA0304050;

	private static String name = "";

	private List<LevelList.GroupInfo> shown;
	private int listScroll;

	GroupsScreen() {
		super(Component.translatable("gui.levelmod.groups"));
		ClientPlayNetworking.send(new LevelGroupEdit("", LevelGroupEdit.Action.REFRESH, List.of()));
	}

	private LevelList.GroupInfo selected() {
		for (LevelList.GroupInfo info : ClientLevels.groups) if (info.name().equals(name.trim())) return info;
		return null;
	}

	private void send(LevelGroupEdit.Action action, List<java.util.UUID> objects) {
		ClientPlayNetworking.send(new LevelGroupEdit(name, action, objects));
	}

	@Override
	protected void init() {
		shown = ClientLevels.groups;
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN, half = (w - 2) / 2, y = 24;
		EditBox box = new EditBox(font, x, y, w, H, Component.translatable("gui.levelmod.groups.name"));
		box.setMaxLength(LevelGroupEdit.MAX_NAME);
		box.setHint(Component.translatable("gui.levelmod.groups.name"));
		box.setValue(name);
		box.setResponder(value -> name = value);
		addRenderableWidget(box);
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.groups.create"), b -> {
			if (!name.isBlank()) send(LevelGroupEdit.Action.CREATE, List.of());
		}).bounds(x, y, half, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.groups.delete"), b -> {
			if (selected() != null) send(LevelGroupEdit.Action.DELETE, List.of());
			name = "";
			rebuildWidgets();
		}).bounds(x + half + 2, y, half, H).build()).active = selected() != null;
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.groups.add"), b -> draw(LevelGroupEdit.Action.ASSIGN)).bounds(x, y, half, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.groups.remove"), b -> draw(LevelGroupEdit.Action.REMOVE)).bounds(x + half + 2, y, half, H).build()).active = selected() != null;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.close"), b -> onClose()).bounds(x + half + 2, height - MARGIN - H, half, H).build());
	}

	/** Объекты выбираются в мире инструментом «Выбор» BuildMod (ПКМ по сущности, Enter — готово): окно закрывается и открывается снова с результатом. */
	private void draw(LevelGroupEdit.Action action) {
		if (name.isBlank()) return;
		minecraft.gui.setScreen(null);
		com.buildmod.client.PickTool.start(Component.translatable("message.levelmod.group.pick_hint", name.trim()).getString(), picked -> {
			List<java.util.UUID> ids = picked.stream().flatMap(p -> p.entity().stream()).toList();
			if (ids.isEmpty()) return Component.translatable("message.levelmod.group.pick_none").getString();
			send(action, ids);
			minecraft.gui.setScreen(new GroupsScreen());
			return null;
		});
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(new LevelManagerScreen());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		// числа членов считаются по загруженным чанкам: пока окно открыто, запрашиваем свежие раз в две секунды
		if (minecraft.player != null && minecraft.player.tickCount % 40 == 0) ClientPlayNetworking.send(new LevelGroupEdit("", LevelGroupEdit.Action.REFRESH, List.of()));
		if (!shown.equals(ClientLevels.groups)) rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && event.x() >= MARGIN && event.x() < MARGIN + LIST_W && event.y() >= 24 && event.y() < height - MARGIN) {
			int row = ((int) event.y() - 24 + listScroll) / CARD_H;
			if (row >= 0 && row < ClientLevels.groups.size()) {
				name = ClientLevels.groups.get(row).name();
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x < MARGIN + LIST_W) listScroll = Math.max(0, Math.min(listScroll - (int) Math.signum(scrollY) * CARD_H, Math.max(0, ClientLevels.groups.size() * CARD_H - (height - MARGIN - 24))));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, title, MARGIN, 9, WHITE);
		int top = 24, bottom = height - MARGIN;
		if (ClientLevels.groups.isEmpty()) g.text(font, Component.translatable("gui.levelmod.groups.empty"), MARGIN + 3, top + 3, GRAY);
		g.enableScissor(MARGIN, top, MARGIN + LIST_W, bottom);
		for (int i = 0; i < ClientLevels.groups.size(); i++) {
			LevelList.GroupInfo info = ClientLevels.groups.get(i);
			int y = top + i * CARD_H - listScroll;
			if (y + CARD_H <= top || y >= bottom) continue;
			g.fill(MARGIN, y, MARGIN + LIST_W, y + CARD_H - 1, info.name().equals(name.trim()) ? CARD_SELECTED : CARD);
			g.text(font, firstLine(Component.literal(info.name()), LIST_W - 30), MARGIN + 3, y + 3, info.count() > 0 ? GREEN : YELLOW);
			String count = String.valueOf(info.count());
			g.text(font, count, MARGIN + LIST_W - 4 - font.width(count), y + 3, GRAY);
		}
		g.disableScissor();
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN, y = 24 + 3 * ROW + 2;
		for (FormattedCharSequence line : font.split(Component.translatable("gui.levelmod.groups.hint"), w)) {
			g.text(font, line, x, y, GRAY);
			y += LINE;
		}
	}

	private FormattedCharSequence firstLine(Component text, int width) {
		List<FormattedCharSequence> lines = font.split(text, Math.max(1, width));
		return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.getFirst();
	}
}
