package com.smeshariki.level.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.task.TaskArea;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Пока автор создаёт или правит локации (окно локаций открыто или он ушёл рисовать область), в мире подсвечены все отмеченные: точка — столбом, область — прозрачной
 * завесой по контуру с яркой кромкой. У каждой свой цвет по имени (тот же квадратик стоит в списке окна); выбранная в окне ярче. Имя ближайшей локации видно над панелью.
 */
final class LocationsHighlight {
	/** Дальше этого от игрока локации не рисуем, блоков. */
	private static final double RANGE = 160;
	/** Высота завесы и столба, блоков; кромка завесы толщиной. */
	private static final float HEIGHT = 10f, POINT_HEIGHT = 3f, EDGE = 0.18f;

	private LocationsHighlight() {
	}

	static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(LocationsHighlight::submit);
		ClientTickEvents.END_CLIENT_TICK.register(LocationsHighlight::tick);
	}

	/** Цвет локации по имени (RGB). */
	static int colorOf(String name) {
		return Mth.hsvToRgb(Math.floorMod(name.hashCode() * 31, 360) / 360f, 0.65f, 1f);
	}

	private static boolean active() {
		return ClientLevels.editing() && (ClientLevels.inLocations || ClientLevels.locationsOpen);
	}

	/** Имя ближайшей локации: в её области или не дальше 8 блоков от точки. */
	private static void tick(Minecraft mc) {
		if (!active() || mc.player == null || mc.level == null || mc.level.getGameTime() % 5 != 0) return;
		Vec3 p = mc.player.position();
		String nearest = null;
		double best = 8;
		for (LevelList.LocationInfo info : ClientLevels.locations.marked()) {
			if (info.point().isPresent()) {
				double d = info.point().get().distanceTo(p);
				if (d < best) {
					best = d;
					nearest = info.name();
				}
			}
			if (info.area().isPresent() && info.area().get().dimension().equals(mc.level.dimension()) && info.area().get().contains(p)) {
				best = 0;
				nearest = info.name();
			}
		}
		if (nearest != null) mc.gui.hud.setOverlayMessage(Component.translatable("gui.levelmod.locations.here", Component.literal(nearest).withColor(0xFF000000 | colorOf(nearest))), false);
	}

	private static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!active() || mc.level == null || mc.player == null) return;
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		String chosen = LocationsScreen.currentName();
		PoseStack pose = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(pose, net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads(), (p, c) -> {
			for (LevelList.LocationInfo info : ClientLevels.locations.marked()) {
				boolean selected = info.name().equals(chosen);
				int rgb = colorOf(info.name());
				int curtain = ((selected ? 0x70 : 0x38) << 24) | rgb, edge = ((selected ? 0xE0 : 0x90) << 24) | rgb;
				info.point().ifPresent(point -> {
					if (point.distanceTo(cam) <= RANGE) column(c, p, point.x - cam.x, point.y - cam.y, point.z - cam.z, selected ? 0.45f : 0.3f, curtain, edge);
				});
				info.area().ifPresent(area -> {
					if (area.dimension().equals(mc.level.dimension())) curtain(c, p, area, cam, mc.player.getY(), curtain, edge);
				});
			}
		});
	}

	/** Столб над точкой: четыре грани и яркая «шапка». */
	private static void column(VertexConsumer c, PoseStack.Pose p, double x, double y, double z, float half, int fill, int edge) {
		float x0 = (float) x - half, x1 = (float) x + half, z0 = (float) z - half, z1 = (float) z + half, y0 = (float) y, y1 = y0 + POINT_HEIGHT;
		quad(c, p, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, fill);
		quad(c, p, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, fill);
		quad(c, p, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, fill);
		quad(c, p, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, fill);
		quad(c, p, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, edge);
	}

	/** Завеса по контуру области: у области высоты нет, поэтому от нарисованной высоты, а без неё — от высоты игрока. */
	private static void curtain(VertexConsumer c, PoseStack.Pose p, TaskArea area, Vec3 cam, double fallbackY, int fill, int edge) {
		Vec3 drawn = area.drawnCenter();
		float base = (float) ((drawn != null ? drawn.y : fallbackY) - 1 - cam.y);
		for (List<TaskArea.Point> loop : area.loops()) {
			for (int i = 0; i < loop.size(); i++) {
				TaskArea.Point a = loop.get(i), b = loop.get((i + 1) % loop.size());
				if (Math.min(Math.hypot(a.x() - cam.x, a.z() - cam.z), Math.hypot(b.x() - cam.x, b.z() - cam.z)) > RANGE) continue;
				float ax = (float) (a.x() - cam.x), az = (float) (a.z() - cam.z), bx = (float) (b.x() - cam.x), bz = (float) (b.z() - cam.z);
				quad(c, p, ax, base, az, bx, base, bz, bx, base + HEIGHT, bz, ax, base + HEIGHT, az, fill);
				quad(c, p, ax, base + HEIGHT - EDGE, az, bx, base + HEIGHT - EDGE, bz, bx, base + HEIGHT, bz, ax, base + HEIGHT, az, edge);
				quad(c, p, ax, base, az, bx, base, bz, bx, base + EDGE, bz, ax, base + EDGE, az, edge);
			}
		}
	}

	/** Четырёхугольник с обеих сторон: виден при любом отсечении. */
	private static void quad(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int color) {
		c.addVertex(p, x0, y0, z0).setColor(color);
		c.addVertex(p, x1, y1, z1).setColor(color);
		c.addVertex(p, x2, y2, z2).setColor(color);
		c.addVertex(p, x3, y3, z3).setColor(color);
		c.addVertex(p, x3, y3, z3).setColor(color);
		c.addVertex(p, x2, y2, z2).setColor(color);
		c.addVertex(p, x1, y1, z1).setColor(color);
		c.addVertex(p, x0, y0, z0).setColor(color);
	}
}
