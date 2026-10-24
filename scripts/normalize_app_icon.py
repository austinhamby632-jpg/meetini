from pathlib import Path
from PIL import Image

SOURCE = Path('/home/ubuntu/meetin-app/app/src/main/res/drawable-nodpi/app_icon.png')
OUTPUT = SOURCE.with_suffix('.normalized.png')

with Image.open(SOURCE) as image:
    rgba = image.convert('RGBA')
    pixels = rgba.load()
    width, height = rgba.size

    # Locate the non-black icon artwork generated around the emblem, leaving
    # the rounded-square artwork intact while excluding the surrounding canvas.
    points = []
    for y in range(height):
        for x in range(width):
            r, g, b, _ = pixels[x, y]
            if max(r, g, b) > 12:
                points.append((x, y))
    if not points:
        raise RuntimeError('No icon artwork detected')

    min_x = min(x for x, _ in points)
    max_x = max(x for x, _ in points)
    min_y = min(y for _, y in points)
    max_y = max(y for _, y in points)
    side = max(max_x - min_x + 1, max_y - min_y + 1)
    center_x = (min_x + max_x) / 2
    center_y = (min_y + max_y) / 2
    left = round(center_x - side / 2)
    top = round(center_y - side / 2)
    right = left + side
    bottom = top + side

    if left < 0 or top < 0 or right > width or bottom > height:
        raise RuntimeError('Detected icon bounds exceed source image')

    icon = rgba.crop((left, top, right, bottom)).resize((512, 512), Image.Resampling.LANCZOS)
    icon_pixels = icon.load()
    radius = 92

    # Make the outside of the rounded-square icon transparent so launchers do
    # not display the original black generation canvas as a rectangular frame.
    for y in range(512):
        for x in range(512):
            dx = max(radius - x, 0, x - (511 - radius))
            dy = max(radius - y, 0, y - (511 - radius))
            outside = dx * dx + dy * dy > radius * radius
            if outside:
                r, g, b, _ = icon_pixels[x, y]
                icon_pixels[x, y] = (r, g, b, 0)

    icon.save(OUTPUT, format='PNG', optimize=True)

OUTPUT.replace(SOURCE)
print(f'Wrote {SOURCE} ({SOURCE.stat().st_size} bytes)')
