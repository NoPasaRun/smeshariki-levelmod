package com.smeshariki.level.client;

import java.util.List;

import com.smeshariki.level.net.LevelGroupEdit;
import com.smeshariki.level.net.LevelList;
import com.smeshariki.ui.CardList;
import com.smeshariki.ui.Ui;
import com.smeshariki.ui.UiScreen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Реестр групп объектов: слева группы с числом членов (среди загруженных), справа имя, «Создать», «Добавить в область» и «Убрать из области»
 * (область рисуется инструментом BuildMod, как у локаций; в неё попадают объекты любого вида, которым можно задать группу, например шары и маркеры),
 * «Удалить» (снимает группу и со всех членов). Группу на объект файлы уровней и механики ссылаются по имени, параметром {@code group}.
 */
final class GroupsScreen extends UiScreen {
	private static final int MARGIN = 8, LIST_W = 150, CARD_H = 16, H = 20, ROW = 24, LINE = 11;
	private static final int WHITE = Ui.TEXT, GRAY = Ui.HINT, GREEN = Ui.GOOD, YELLOW = Ui.ACCENT;

	private static String name = "";

	private List<LevelList.GroupInfo> shown;
	private final CardList cards = new CardList(CARD_H, 1);

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
		cards.layout(MARGIN, 24, LIST_W, height - MARGIN);
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
	public void tick() {
		super.tick();
		// числа членов считаются по загруженным чанкам: пока окно открыто, запрашиваем свежие раз в две секунды
		if (minecraft.player != null && minecraft.player.tickCount % 40 == 0) ClientPlayNetworking.send(new LevelGroupEdit("", LevelGroupEdit.Action.REFRESH, List.of()));
		if (!shown.equals(ClientLevels.groups) && !isEditing()) rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int row = cards.rowAt(event.x(), event.y(), ClientLevels.groups.size());
		if (event.button() == 0 && row >= 0) {
			name = ClientLevels.groups.get(row).name();
			rebuildWidgets();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		cards.wheel(x, scrollY, ClientLevels.groups.size());
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, title, MARGIN, 9, WHITE);
		int top = 24, bottom = height - MARGIN;
		if (ClientLevels.groups.isEmpty()) g.text(font, Component.translatable("gui.levelmod.groups.empty"), MARGIN + 3, top + 3, GRAY);
		cards.draw(g, ClientLevels.groups.size(), mouseX, mouseY, (i, y, hover) -> {
			LevelList.GroupInfo info = ClientLevels.groups.get(i);
			cards.background(g, MARGIN, y, info.name().equals(name.trim()), hover);
			g.text(font, firstLine(Component.literal(info.name()), LIST_W - 30), MARGIN + 3, y + 3, info.count() > 0 ? GREEN : YELLOW);
			String count = String.valueOf(info.count());
			g.text(font, count, MARGIN + LIST_W - 4 - font.width(count), y + 3, GRAY);
		});
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN, y = 24 + 3 * ROW + 2;
		for (FormattedCharSequence line : font.split(Component.translatable("gui.levelmod.groups.hint"), w)) {
			g.text(font, line, x, y, GRAY);
			y += LINE;
		}
	}

	private FormattedCharSequence firstLine(Component text, int width) {
		return Ui.firstLine(font, text, width);
	}
}
