from pathlib import Path
from PIL import Image

SOURCE = Path('/home/ubuntu/upload/Gemini_Generated_Image_tfhazdtfhazdtfha.jpeg')
OUTPUT = Path('/home/ubuntu/meetin-app/app/src/main/res/drawable-nodpi/app_icon.png')

# The supplied canvas is 1408x768. The app mark occupies the centered upper
# square; crop only that mark so the launcher icon does not include the wordmark.
with Image.open(SOURCE) as image:
    image = image.convert('RGB')
    crop = image.crop((510, 75, 970, 535))
    crop = crop.resize((512, 512), Image.Resampling.LANCZOS)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    crop.save(OUTPUT, format='PNG', optimize=True)

print(f'Wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)')
