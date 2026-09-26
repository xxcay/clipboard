"""Draws app.ico: a white clipboard with sync arrows on an orange gradient. Needs Pillow."""
import sys
from PIL import Image, ImageDraw

S = 1024  # draw large, then downscale for smooth edges
ORANGE_TOP = (255, 146, 64)
ORANGE_BOTTOM = (255, 84, 24)


def gradient(size):
    g = Image.new("RGBA", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size - 2)
            px[x, y] = tuple(int(a + (b - a) * t) for a, b in zip(ORANGE_TOP, ORANGE_BOTTOM)) + (255,)
    return g


def draw() -> Image.Image:
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((24, 24, S - 24, S - 24), radius=240, fill=255)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(gradient(S), (0, 0), mask)
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    accent = ORANGE_BOTTOM + (255,)
    # clipboard board and clip
    d.rounded_rectangle((262, 222, 762, 842), radius=84, fill=white)
    d.rounded_rectangle((382, 160, 642, 300), radius=52, fill=white)
    d.rounded_rectangle((420, 196, 604, 264), radius=30, fill=(255, 120, 44, 255))
    # two-way arrows: sync between devices
    d.rounded_rectangle((352, 430, 632, 482), radius=26, fill=accent)
    d.polygon([(612, 380), (692, 456), (612, 532)], fill=accent)
    d.rounded_rectangle((392, 600, 672, 652), radius=26, fill=accent)
    d.polygon([(412, 550), (332, 626), (412, 702)], fill=accent)
    return img


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "app.ico"
    big = draw().resize((256, 256), Image.LANCZOS)
    big.save(out, sizes=[(s, s) for s in [16, 20, 24, 32, 40, 48, 64, 128, 256]])
    if len(sys.argv) > 2:
        draw().resize((int(sys.argv[2]), int(sys.argv[2])), Image.LANCZOS).save(out.replace(".ico", f"-{sys.argv[2]}.png"))
