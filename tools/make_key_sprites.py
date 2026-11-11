#!/usr/bin/env python3
"""Рисует спрайты клавиш для описаний (client/RichText) в src/main/resources/assets/levelmod/textures/gui/sprites/key/.

cap.png — пустая клавиша 12x12, растягивается по ширине (nine-slice, cap.png.mcmeta), текст на неё кладёт RichText.
Остальные — клавиши целиком с нарисованным символом; их ширина должна совпадать с таблицей SPRITE_WIDTH в RichText.
PNG — исходник правды: можно править руками, скрипт нужен, чтобы воссоздать набор. Запуск: python3 tools/make_key_sprites.py
"""
import json
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "levelmod", "textures", "gui", "sprites", "key")
H = 12  # высота клавиши: 0 — кромка, 1..9 — лицо (буквы шрифта занимают 2..8), 10 — «бок», 11 — кромка
EDGE, FACE, LIP, INK = (0x2B, 0x34, 0x42, 255), (0xF4, 0xF6, 0xF9, 255), (0xAE, 0xB9, 0xC6, 255), (0x2B, 0x34, 0x42, 255)

UP = ["..#..", ".###.", "#####", "..#..", "..#.."]
LEFT = ["".join(UP[r][c] for r in range(5)) for c in range(5)]
DOWN = UP[::-1]
RIGHT = [row[::-1] for row in LEFT]

GLYPHS = {
	"up": (11, UP, None),
	"down": (11, DOWN, None),
	"left": (11, LEFT, None),
	"right": (11, RIGHT, None),
	"shift": (13, ["...#...", "..#.#..", ".#...#.", "###.###", "..#.#..", "..#.#..", "..###.."], None),
	"enter": (13, ["......#", "......#", "..#...#", ".######", "..#...."], None),
	"backspace": (13, ["...######", "..#.....#", ".#..#.#.#", "#....#..#", ".#..#.#.#", "..#.....#", "...######"], None),
	"tab": (12, ["...#...#", "....#..#", "######.#", "....#..#", "...#...#"], None),
	"space": (25, ["#...........#", "#...........#", "#############"], 5),
}


def blank_key(w):
	img = Image.new("RGBA", (w, H), (0, 0, 0, 0))
	px = img.load()
	for x in range(1, w - 1):
		px[x, 0] = EDGE
		px[x, H - 1] = EDGE
	for y in range(1, H - 1):
		px[0, y] = EDGE
		px[w - 1, y] = EDGE
		for x in range(1, w - 1):
			px[x, y] = LIP if y == H - 2 else FACE
	return img


def main():
	os.makedirs(OUT, exist_ok=True)
	cap = blank_key(12)
	cap.save(os.path.join(OUT, "cap.png"))
	with open(os.path.join(OUT, "cap.png.mcmeta"), "w") as f:
		json.dump({"gui": {"scaling": {"type": "nine_slice", "width": 12, "height": H, "border": 3}}}, f, indent=2)
	for name, (w, rows, top) in GLYPHS.items():
		img = blank_key(w)
		gh, gw = len(rows), len(rows[0])
		x0 = 1 + (w - 2 - gw) // 2
		y0 = top if top is not None else 2 + (7 - gh) // 2
		for y, row in enumerate(rows):
			for x, ch in enumerate(row):
				if ch == "#":
					img.putpixel((x0 + x, y0 + y), INK)
		img.save(os.path.join(OUT, name + ".png"))
		print(name, w, "x", H)


if __name__ == "__main__":
	main()
