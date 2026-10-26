# Zeichnet das Fuchs-Icon und legt es im Ordner "icons" ab.
# Wird vom GitHub-Workflow vor setup-android.js ausgeführt.
from PIL import Image, ImageDraw
import os

OR = (245, 124, 31, 255)
OR2 = (214, 92, 12, 255)
CR = (255, 241, 220, 255)
DK = (42, 22, 8, 255)
BG = (44, 102, 245, 255)

HEAD = [(12, 10), (38, 30), (62, 30), (88, 10), (93, 50), (50, 93), (7, 50)]
LC = [(7, 50), (38, 60), (50, 93)]
RC = [(93, 50), (62, 60), (50, 93)]
LE = [(17, 19), (31, 31), (18, 42)]
RE = [(83, 19), (69, 31), (82, 42)]


def fox(d, u, cx, cy):
    def P(pts):
        return [(cx + (x - 50) * u, cy + (y - 50) * u) for x, y in pts]
    d.polygon(P(HEAD), fill=OR)
    d.polygon(P(LC), fill=CR)
    d.polygon(P(RC), fill=CR)
    d.polygon(P(LE), fill=OR2)
    d.polygon(P(RE), fill=OR2)
    d.polygon(P([(30, 47), (42, 52), (31, 54)]), fill=DK)
    d.polygon(P([(70, 47), (58, 52), (69, 54)]), fill=DK)
    d.polygon(P([(43, 81), (57, 81), (50, 93)]), fill=DK)


def render(size, mode):
    S = 4
    N = size * S
    im = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if mode == 'fg':
        fox(d, 0.58 * N / 80, N / 2, N / 2)
    else:
        if mode == 'square':
            d.rounded_rectangle((0, 0, N - 1, N - 1), radius=int(N * 0.22), fill=BG)
        else:
            d.ellipse((0, 0, N - 1, N - 1), fill=BG)
        fox(d, 0.72 * N / 80, N / 2, N / 2 + N * 0.01)
    return im.resize((size, size), Image.LANCZOS)


DENS = {'mdpi': (48, 108), 'hdpi': (72, 162), 'xhdpi': (96, 216),
        'xxhdpi': (144, 324), 'xxxhdpi': (192, 432)}

for k, (legacy, fg) in DENS.items():
    p = 'icons/mipmap-' + k
    os.makedirs(p, exist_ok=True)
    render(legacy, 'square').save(p + '/ic_launcher.png')
    render(legacy, 'round').save(p + '/ic_launcher_round.png')
    render(fg, 'fg').save(p + '/ic_launcher_foreground.png')
print('Fuchs-Icons erzeugt')
