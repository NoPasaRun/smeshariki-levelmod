package com.smeshariki.level.task;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Область задачи: один или несколько замкнутых контуров в плоскости XZ, по высоте ограничений нет. Рисуется инструментом своей формы из
 * BuildMod. Контур внутри контура вырезает из области дыру. Исходный граф хранится рядом, чтобы границу можно было поправить, а не рисовать заново.
 *
 * @param loops контуры, изгибы уже развёрнуты в ломаные
 * @param points точки исходного графа
 * @param edges рёбра исходного графа: {@code a, b, изгиб, угол}
 */
public record TaskArea(ResourceKey<Level> dimension, List<List<Point>> loops, List<BlockPos> points, List<List<Integer>> edges) implements com.smeshariki.gameplay.api.Mechanics.Area {
	public record Point(double x, double z) {
		static final Codec<Point> CODEC = Codec.DOUBLE.listOf().comapFlatMap(
				list -> list.size() == 2 ? DataResult.success(new Point(list.get(0), list.get(1))) : DataResult.error(() -> "Точка контура: нужны x и z"),
				point -> List.of(point.x, point.z));
	}

	public static final Codec<TaskArea> CODEC = RecordCodecBuilder.create(i -> i.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(TaskArea::dimension),
			Point.CODEC.listOf().listOf().fieldOf("loops").forGetter(TaskArea::loops),
			BlockPos.CODEC.listOf().optionalFieldOf("points", List.of()).forGetter(TaskArea::points),
			Codec.INT.listOf().listOf().optionalFieldOf("edges", List.of()).forGetter(TaskArea::edges)
	).apply(i, TaskArea::new));

	/** Ребро графа как ломаная: кривую считает BuildMod, этому коду он не нужен. */
	@FunctionalInterface
	public interface Flattener {
		List<double[]> flatten(double[] a, double[] b, int bend, int angle);
	}

	/** Граф не подходит как граница. */
	public static final class InvalidOutline extends Exception {
		public final String translationKey;

		InvalidOutline(String translationKey) {
			this.translationKey = translationKey;
		}
	}

	/**
	 * Собирает область из нарисованного графа. Незамкнутая цепочка замыкается прямой от конца к началу, чтобы не требовать точного попадания
	 * в первую точку. Развилок быть не должно: у них нет однозначной границы.
	 */
	public static TaskArea fromGraph(ResourceKey<Level> dimension, List<BlockPos> points, List<List<Integer>> edges, Flattener flattener) throws InvalidOutline {
		List<List<Integer>> atPoint = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) atPoint.add(new ArrayList<>());
		for (int e = 0; e < edges.size(); e++) {
			atPoint.get(edges.get(e).get(0)).add(e);
			atPoint.get(edges.get(e).get(1)).add(e);
		}
		for (List<Integer> at : atPoint) if (at.size() > 2) throw new InvalidOutline("message.levelmod.outline.branch");
		boolean[] used = new boolean[edges.size()];
		List<List<Point>> loops = new ArrayList<>();
		// сначала цепочки от их концов, потом то, что осталось: это уже циклы
		for (int pass = 1; pass <= 2; pass++) {
			for (int start = 0; start < points.size(); start++) {
				if (atPoint.get(start).size() != pass || !hasUnused(atPoint.get(start), used)) continue;
				List<Point> loop = walk(start, points, edges, atPoint, used, flattener);
				if (enclosesArea(loop)) loops.add(loop);
			}
		}
		if (loops.isEmpty()) throw new InvalidOutline("message.levelmod.outline.empty");
		return new TaskArea(dimension, loops, List.copyOf(points), List.copyOf(edges));
	}

	private static boolean hasUnused(List<Integer> at, boolean[] used) {
		for (int e : at) if (!used[e]) return true;
		return false;
	}

	private static List<Point> walk(int start, List<BlockPos> points, List<List<Integer>> edges, List<List<Integer>> atPoint, boolean[] used, Flattener flattener) {
		List<Point> loop = new ArrayList<>();
		int current = start;
		while (true) {
			int next = -1;
			for (int e : atPoint.get(current)) if (!used[e]) next = e;
			if (next < 0) break;
			used[next] = true;
			List<Integer> edge = edges.get(next);
			int a = edge.get(0), b = edge.get(1);
			List<double[]> line = flattener.flatten(center(points.get(a)), center(points.get(b)), edge.size() > 2 ? edge.get(2) : 0, edge.size() > 3 ? edge.get(3) : 0);
			boolean forward = a == current;
			for (int k = 0; k < line.size() - 1; k++) {
				double[] p = line.get(forward ? k : line.size() - 1 - k);
				loop.add(new Point(p[0], p[2]));
			}
			current = forward ? b : a;
		}
		if (current != start) loop.add(new Point(points.get(current).getX() + 0.5, points.get(current).getZ() + 0.5));
		return loop;
	}

	private static double[] center(BlockPos pos) {
		return new double[] { pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5 };
	}

	/** Площадь по формуле Гаусса: контур, вытянутый в линию (например, нарисованный в вертикальной плоскости), областью не считается. */
	private static boolean enclosesArea(List<Point> loop) {
		double doubled = 0;
		for (int i = 0; i < loop.size(); i++) {
			Point p = loop.get(i), q = loop.get((i + 1) % loop.size());
			doubled += p.x * q.z - q.x * p.z;
		}
		return Math.abs(doubled) >= 2;
	}

	/** Чёт-нечет: луч вдоль X пересекает границу нечётное число раз только изнутри. */
	public boolean contains(Vec3 pos) {
		boolean inside = false;
		for (List<Point> loop : loops) {
			for (int i = 0, j = loop.size() - 1; i < loop.size(); j = i++) {
				Point p = loop.get(i), q = loop.get(j);
				if ((p.z > pos.z) != (q.z > pos.z) && pos.x < (q.x - p.x) * (pos.z - p.z) / (q.z - p.z) + p.x) inside = !inside;
			}
		}
		return inside;
	}

	/** Ближайшая точка контуров по горизонтали, сдвинутая на {@code 0,05} к середине отрезка внутрь (мяч GameplayMod встаёт на границу). */
	@Override
	public @org.jetbrains.annotations.Nullable Vec3 nearestEdge(Vec3 pos) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (List<Point> loop : loops) {
			for (int i = 0, j = loop.size() - 1; i < loop.size(); j = i++) {
				Point p = loop.get(j), q = loop.get(i);
				double ex = q.x - p.x, ez = q.z - p.z, len2 = ex * ex + ez * ez;
				double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((pos.x - p.x) * ex + (pos.z - p.z) * ez) / len2));
				double x = p.x + ex * t, z = p.z + ez * t, d = (x - pos.x) * (x - pos.x) + (z - pos.z) * (z - pos.z);
				if (d >= bestD) continue;
				bestD = d;
				best = new Vec3(x, pos.y, z);
				// внутрь: из двух сторон отрезка та, что в области
				double len = Math.sqrt(len2);
				if (len > 1e-9) {
					Vec3 a = new Vec3(x - ez / len * 0.05, pos.y, z + ex / len * 0.05), b = new Vec3(x + ez / len * 0.05, pos.y, z - ex / len * 0.05);
					best = contains(a) ? a : contains(b) ? b : best;
				}
			}
		}
		return best;
	}

	/** Расстояние по горизонтали до ближайшей вершины границы: для оценки пути. */
	public double distanceTo(Vec3 pos) {
		double best = Double.MAX_VALUE;
		for (List<Point> loop : loops) {
			for (Point p : loop) best = Math.min(best, Math.hypot(p.x - pos.x, p.z - pos.z));
		}
		return best;
	}

	/** Середина вершин; высоты у области нет, поэтому берётся условная. */
	@Override
	public double[] boundsXZ() {
		double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (List<Point> loop : loops) {
			for (Point p : loop) {
				minX = Math.min(minX, p.x());
				minZ = Math.min(minZ, p.z());
				maxX = Math.max(maxX, p.x());
				maxZ = Math.max(maxZ, p.z());
			}
		}
		return minX > maxX ? new double[4] : new double[] {minX, minZ, maxX, maxZ};
	}

	/** Где стоять в области: середина вершин и средняя высота нарисованных точек; {@code null} — точек нет (высота неизвестна). */
	public @org.jetbrains.annotations.Nullable Vec3 drawnCenter() {
		if (points.isEmpty()) return null;
		double y = points.stream().mapToInt(BlockPos::getY).average().orElse(64);
		Vec3 c = center();
		return new Vec3(c.x, y, c.z);
	}

	public Vec3 center() {
		double x = 0, z = 0;
		int count = 0;
		for (List<Point> loop : loops) {
			for (Point p : loop) {
				x += p.x;
				z += p.z;
				count++;
			}
		}
		return new Vec3(x / count, 64, z / count);
	}
}
