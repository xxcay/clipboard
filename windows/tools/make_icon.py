"""Draws app.ico (a clipboard on a blue rounded square). Needs Pillow."""
import sys
from PIL import Image, ImageDraw

S = 1024  # draw large, then downscale for smooth edges


def draw() -> Image.Image:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((32, 32, S - 32, S - 32), radius=220, fill=(37, 99, 235, 255))
    # clipboard board
    d.rounded_rectangle((250, 215, 774, 850), radius=70, fill=(255, 255, 255, 255))
    # clip on top
    d.rounded_rectangle((372, 150, 652, 300), radius=48, fill=(255, 255, 255, 255))
    d.rounded_rectangle((410, 188, 614, 262), radius=30, fill=(37, 99, 235, 255))
    # two-way arrows: sync between devices
    blue = (37, 99, 235, 255)
    d.rounded_rectangle((340, 420, 640, 470), radius=25, fill=blue)
    d.polygon([(620, 370), (700, 445), (620, 520)], fill=blue)
    d.rounded_rectangle((384, 600, 684, 650), radius=25, fill=blue)
    d.polygon([(404, 550), (324, 625), (404, 700)], fill=blue)
    return img


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "app.ico"
    big = draw()
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    big.resize((256, 256), Image.LANCZOS).save(out, sizes=[(s, s) for s in sizes])
    big.resize((256, 256), Image.LANCZOS).save(out.replace(".ico", "-preview.png"))
