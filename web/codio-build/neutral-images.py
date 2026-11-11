# Replaces Stremio's artwork in a built web UI (mascot avatars, empty-state and placeholder pictures, logos)
# with plain neutral images of the same size, so no Stremio branding ships in Codio.
import sys, os
from PIL import Image
d = os.path.join(sys.argv[1], 'images')
for f in os.listdir(d):
    if not f.endswith('.png'):
        continue
    p = os.path.join(d, f)
    size = Image.open(p).size
    if f in ('default_avatar.png', 'anonymous.png'):
        im = Image.new('RGBA', size, (60, 60, 66, 255))      # flat grey avatar
    else:
        im = Image.new('RGBA', size, (0, 0, 0, 0))           # transparent
    im.save(p, optimize=True)
print('neutral images:', d)
