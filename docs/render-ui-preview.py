"""Renders the LayerPad redesign as three phone mock-ups.

This is a *layout render*, not a screenshot: it draws the same colours, dp
measurements and control sizes the Android code uses (Ui.kt / Widgets.kt), so the
design can be reviewed without building and installing the APK.
"""

import sys

sys.path.insert(0, "/home/user/.local/pil")

from PIL import Image, ImageDraw, ImageFont

# ---- palette (mirrors Ui in Widgets.kt) ------------------------------------
BACKGROUND = (0x0F, 0x11, 0x15)
SURFACE = (0x17, 0x1A, 0x20)
SURFACE_HIGH = (0x20, 0x24, 0x2C)
SURFACE_MAX = (0x2A, 0x2F, 0x38)
OUTLINE = (0x33, 0x36, 0x3E)
TEXT = (0xF1, 0xF3, 0xF7)
TEXT_DIM = (0x98, 0xA0, 0xAD)
TEXT_FAINT = (0x6B, 0x73, 0x83)
ACCENT = (0xFF, 0x9E, 0x3D)
ACCENT_SOFT = (0x4A, 0x33, 0x19)
ON_ACCENT = (0x1A, 0x12, 0x06)
CANVAS_BG = (0x0A, 0x0B, 0x0E)
CHECKER_DARK = (0x2E, 0x33, 0x3C)
CHECKER_LIGHT = (0x3A, 0x3F, 0x49)
DANGER = (0xFF, 0x6B, 0x6B)
SUCCESS = (0x57, 0xD9, 0xA3)

SCALE = 3          # render at 3x then downscale, like a xxhdpi phone
DP = SCALE
W, H = 360 * DP, 780 * DP

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_M = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"


def font(size_sp, bold=False, mono=False):
    path = FONT_M if mono else (FONT_B if bold else FONT)
    return ImageFont.truetype(path, int(size_sp * DP))


def rounded(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text(draw, xy, value, f, color, anchor="la"):
    draw.text(xy, value, font=f, fill=color, anchor=anchor)


# ---- icons (rough stand-ins for the in-app vector glyphs) ------------------
def icon(draw, kind, cx, cy, color, box=18):
    s = box * DP
    lw = max(2, int(1.9 * DP))
    if kind == "menu":
        for i, dy in enumerate((-0.45, 0, 0.45)):
            draw.line((cx - s / 2, cy + dy * s, cx + s / 2, cy + dy * s), fill=color, width=lw)
    elif kind == "undo" or kind == "redo":
        d = 1 if kind == "redo" else -1
        draw.arc((cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), 180 if d > 0 else 0,
                 360 if d > 0 else 180, fill=color, width=lw)
        tip = (cx + d * s / 2, cy)
        draw.line((tip[0], tip[1] - s * 0.22, tip[0] + d * s * 0.16, tip[1] + s * 0.1), fill=color, width=lw)
    elif kind == "layers":
        for i, o in enumerate((0, 0.3, 0.6)):
            draw.polygon(
                [(cx, cy - s / 2 + o * s / 2), (cx + s / 2, cy - s / 4 + o * s / 2),
                 (cx, cy + o * s / 2), (cx - s / 2, cy - s / 4 + o * s / 2)],
                outline=color, width=lw,
            )
    elif kind == "move":
        for dx, dy in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            draw.line((cx + dx * s * 0.15, cy + dy * s * 0.15, cx + dx * s / 2, cy + dy * s / 2), fill=color, width=lw)
        draw.line((cx - s / 2, cy, cx + s / 2, cy), fill=color, width=lw)
        draw.line((cx, cy - s / 2, cx, cy + s / 2), fill=color, width=lw)
    elif kind == "brush":
        draw.line((cx - s * 0.45, cy + s * 0.45, cx + s * 0.3, cy - s * 0.3), fill=color, width=lw * 2)
        draw.polygon([(cx - s * 0.5, cy + s * 0.5), (cx - s * 0.18, cy + s * 0.4),
                      (cx - s * 0.4, cy + s * 0.18)], fill=color)
    elif kind == "eraser":
        draw.polygon([(cx - s * 0.45, cy + s * 0.3), (cx - s * 0.05, cy - s * 0.45),
                      (cx + s * 0.45, cy - s * 0.05), (cx + s * 0.05, cy + s * 0.45)], outline=color, width=lw)
        draw.line((cx - s * 0.45, cy + s * 0.3, cx + s * 0.05, cy + s * 0.45), fill=color, width=lw)
    elif kind == "fill":
        draw.polygon([(cx - s * 0.4, cy), (cx, cy - s * 0.45), (cx + s * 0.4, cy),
                      (cx, cy + s * 0.45)], outline=color, width=lw)
        draw.line((cx + s * 0.35, cy - s * 0.35, cx + s * 0.45, cy - s * 0.1), fill=color, width=lw)
    elif kind == "eyedropper":
        draw.line((cx - s * 0.4, cy + s * 0.4, cx + s * 0.25, cy - s * 0.25), fill=color, width=lw)
        draw.polygon([(cx + s * 0.1, cy - s * 0.4), (cx + s * 0.45, cy - s * 0.05),
                      (cx + s * 0.3, cy + s * 0.05), (cx + s * 0.05, cy - s * 0.25)], fill=color)
    elif kind == "text":
        draw.line((cx - s * 0.4, cy - s * 0.4, cx + s * 0.4, cy - s * 0.4), fill=color, width=lw)
        draw.line((cx, cy - s * 0.4, cx, cy + s * 0.45), fill=color, width=lw)
    elif kind == "crop":
        draw.line((cx - s * 0.45, cy - s * 0.3, cx - s * 0.45, cy + s * 0.45), fill=color, width=lw)
        draw.line((cx - s * 0.45, cy + s * 0.45, cx + s * 0.45, cy + s * 0.45), fill=color, width=lw)
        draw.line((cx + s * 0.45, cy - s * 0.45, cx + s * 0.45, cy + s * 0.3), fill=color, width=lw)
        draw.line((cx - s * 0.2, cy - s * 0.45, cx + s * 0.45, cy - s * 0.45), fill=color, width=lw)
    elif kind == "add":
        draw.line((cx, cy - s / 2, cx, cy + s / 2), fill=color, width=lw)
        draw.line((cx - s / 2, cy, cx + s / 2, cy), fill=color, width=lw)
    elif kind == "remove":
        draw.line((cx - s / 2, cy, cx + s / 2, cy), fill=color, width=lw)
    elif kind == "fit":
        d = s * 0.42
        for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            draw.line((cx + sx * d, cy + sy * d, cx + sx * d * 0.35, cy + sy * d), fill=color, width=lw)
            draw.line((cx + sx * d, cy + sy * d, cx + sx * d, cy + sy * d * 0.35), fill=color, width=lw)
    elif kind == "eye":
        draw.ellipse((cx - s * 0.45, cy - s * 0.28, cx + s * 0.45, cy + s * 0.28), outline=color, width=lw)
        draw.ellipse((cx - s * 0.12, cy - s * 0.12, cx + s * 0.12, cy + s * 0.12), fill=color)
    elif kind == "duplicate":
        rounded(draw, (cx - s * 0.42, cy - s * 0.42, cx + s * 0.18, cy + s * 0.18), int(2 * DP), outline=color, width=lw)
        rounded(draw, (cx - s * 0.18, cy - s * 0.18, cx + s * 0.42, cy + s * 0.42), int(2 * DP), outline=color, width=lw)
    elif kind == "trash":
        draw.line((cx - s * 0.4, cy - s * 0.3, cx + s * 0.4, cy - s * 0.3), fill=color, width=lw)
        draw.rectangle((cx - s * 0.28, cy - s * 0.3, cx + s * 0.28, cy + s * 0.45), outline=color, width=lw)
    elif kind == "open":
        draw.polygon([(cx - s * 0.45, cy + s * 0.35), (cx - s * 0.45, cy - s * 0.3),
                      (cx - s * 0.05, cy - s * 0.3), (cx + s * 0.05, cy - s * 0.12),
                      (cx + s * 0.45, cy - s * 0.12), (cx + s * 0.45, cy + s * 0.35)], outline=color, width=lw)
    elif kind == "new":
        rounded(draw, (cx - s * 0.35, cy - s * 0.45, cx + s * 0.35, cy + s * 0.45), int(2 * DP), outline=color, width=lw)
        draw.line((cx, cy - s * 0.2, cx, cy + s * 0.2), fill=color, width=lw)
        draw.line((cx - s * 0.2, cy, cx + s * 0.2, cy), fill=color, width=lw)
    elif kind == "image":
        rounded(draw, (cx - s * 0.45, cy - s * 0.38, cx + s * 0.45, cy + s * 0.38), int(2 * DP), outline=color, width=lw)
        draw.ellipse((cx - s * 0.3, cy - s * 0.25, cx - s * 0.12, cy - s * 0.07), fill=color)
        draw.polygon([(cx - s * 0.35, cy + s * 0.3), (cx - s * 0.05, cy - s * 0.05), (cx + s * 0.38, cy + s * 0.3)], outline=color, width=lw)
    elif kind == "close":
        draw.line((cx - s * 0.35, cy - s * 0.35, cx + s * 0.35, cy + s * 0.35), fill=color, width=lw)
        draw.line((cx + s * 0.35, cy - s * 0.35, cx - s * 0.35, cy + s * 0.35), fill=color, width=lw)


def icon_button(draw, kind, x, y, size_dp, color, fill=None):
    """Mirrors Ui.iconButton: a square touch target with an 18 dp glyph in the middle."""
    if fill:
        rounded(draw, (x, y, x + size_dp * DP, y + size_dp * DP), int(14 * DP), fill=fill)
    icon(draw, kind, x + size_dp * DP / 2, y + size_dp * DP / 2, color)


def checkerboard(img, box, cell=8):
    draw = ImageDraw.Draw(img)
    x0, y0, x1, y1 = box
    draw.rectangle(box, fill=CHECKER_DARK)
    y = y0
    row = 0
    while y < y1:
        x = x0 if row % 2 == 0 else x0 + cell * DP
        while x < x1:
            draw.rectangle((x, y, min(x + cell * DP, x1), min(y + cell * DP, y1)), fill=CHECKER_LIGHT)
            x += cell * DP * 2
        y += cell * DP
        row += 1


def photo(img, box, label=True):
    """A stand-in 'photo' layer so the canvas has something to show."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    grad = Image.new("RGB", (max(1, w), max(1, h)))
    px = grad.load()
    for y in range(h):
        for x in range(0, w, 1):
            fx, fy = x / max(1, w), y / max(1, h)
            r = int(38 + 150 * fx + 40 * (1 - fy))
            g = int(60 + 90 * fy + 30 * fx)
            b = int(90 + 120 * (1 - fx) + 20 * fy)
            px[x, y] = (min(r, 255), min(g, 255), min(b, 255))
    img.paste(grad, (x0, y0))
    if not label:
        return
    draw = ImageDraw.Draw(img)
    # Text layer, on top of the photo. Only on the canvas: PIL does not clip, and a
    # 34 sp word would spill out of a 44 dp thumbnail.
    f = font(34, bold=True)
    text(draw, ((x0 + x1) / 2, y0 + h * 0.42), "SUMMER", f, (255, 255, 255), "mm")


def canvas_scene(draw, img, with_doc=True):
    """Everything between the top bar and the dock: document + zoom stack."""
    if with_doc:
        doc = (46 * DP, 150 * DP, 314 * DP, 486 * DP)
        draw.rectangle((doc[0] + 6 * DP, doc[1] + 8 * DP, doc[2] + 8 * DP, doc[3] + 10 * DP), fill=(0, 0, 0))
        draw.rectangle((doc[0] + 3 * DP, doc[1] + 4 * DP, doc[2] + 4 * DP, doc[3] + 5 * DP), fill=(0, 0, 0, 120))
        checkerboard(img, doc)
        photo(img, doc)
    # zoom stack, right edge
    x = W - 10 * DP - 48 * DP
    y = int(H * 0.36)
    rounded(draw, (x, y, x + 48 * DP, y + 48 * DP * 4), int(24 * DP), fill=SURFACE_HIGH, outline=OUTLINE)
    text(draw, (x + 24 * DP, y + 24 * DP), "24%", font(11.5, bold=True, mono=True), TEXT, "mm")
    icon(draw, "add", x + 24 * DP, y + 72 * DP, TEXT_DIM)
    icon(draw, "fit", x + 24 * DP, y + 120 * DP, TEXT_DIM)
    icon(draw, "remove", x + 24 * DP, y + 168 * DP, TEXT_DIM)


def top_bar(draw, title, meta, dirty=True):
    y = 10 * DP
    rounded(draw, (10 * DP, y, W - 10 * DP, y + 56 * DP), int(20 * DP), fill=SURFACE_HIGH, outline=OUTLINE)
    icon_button(draw, "menu", 14 * DP, y + 5 * DP, 46, TEXT)
    text(draw, (72 * DP, y + 20 * DP), title + ("  •" if dirty else ""), font(15, bold=True), TEXT)
    text(draw, (72 * DP, y + 40 * DP), meta, font(11), TEXT_FAINT)
    icon_button(draw, "undo", W - 116 * DP, y + 5 * DP, 46, TEXT_DIM)
    icon_button(draw, "redo", W - 62 * DP, y + 5 * DP, 46, TEXT_DIM)


def options_strip(draw, tool="brush"):
    h = 64 * DP
    y = H - 10 * DP - 58 * DP - 8 * DP - h
    rounded(draw, (10 * DP, y, W - 10 * DP, y + h), int(20 * DP), fill=SURFACE, outline=OUTLINE)
    text(draw, (24 * DP, y + h / 2), "Brush", font(12, bold=True), ACCENT)
    # two slider rows, side by side (size, hardness), then a clipped third
    for i, (label, value, pct) in enumerate((("Size", "24 px", 0.34), ("Hardness", "80%", 0.8), ("Flow", "100%", 1.0))):
        x = (100 + i * 168) * DP
        text(draw, (x, y + 16 * DP), label.upper(), font(10.5, bold=True), TEXT_FAINT)
        text(draw, (x + 152 * DP, y + 16 * DP), value, font(12, bold=True, mono=True), TEXT, "ra")
        track = (x, y + 40 * DP, x + 152 * DP, y + 46 * DP)
        rounded(draw, track, 3 * DP, fill=SURFACE_MAX)
        rounded(draw, (track[0], track[1], track[0] + (track[2] - track[0]) * pct, track[3]), 3 * DP, fill=ACCENT)
        draw.ellipse((track[0] + (track[2] - track[0]) * pct - 8 * DP, y + 35 * DP,
                      track[0] + (track[2] - track[0]) * pct + 8 * DP, y + 51 * DP), fill=TEXT)


def dock(draw, active="brush", swatch=(0xFF, 0x9E, 0x3D), clipped=True):
    h = 58 * DP
    y = H - 10 * DP - h
    rounded(draw, (10 * DP, y, W - 10 * DP, y + h), int(20 * DP), fill=SURFACE_HIGH, outline=OUTLINE)
    tools = ["move", "brush", "eraser", "fill", "eyedropper", "text", "crop"]
    x = 16 * DP
    for name in tools:
        if clipped and x + 46 * DP > W - 10 * DP - 100 * DP:
            break
        if name == active:
            rounded(draw, (x, y + 6 * DP, x + 46 * DP, y + 52 * DP), int(14 * DP), fill=ACCENT_SOFT)
        icon(draw, name, x + 23 * DP, y + 29 * DP, ACCENT if name == active else TEXT_DIM)
        x += 46 * DP
    if clipped:
        # the row scrolls; fade the clipped edge
        for i in range(24):
            a = int(255 * (1 - i / 24))
            draw.line((x + i * DP, y + 4 * DP, x + i * DP, y + h - 4 * DP), fill=(0x20, 0x24, 0x2C, a))
        x = W - 10 * DP - 100 * DP
        draw.line((x, y + 14 * DP, x, y + 44 * DP), fill=OUTLINE)
        x += 8 * DP
        draw.ellipse((x + 12 * DP, y + 18 * DP, x + 34 * DP, y + 40 * DP), fill=swatch, outline=(0x66, 0x66, 0x66), width=2)
        icon(draw, "layers", x + 69 * DP, y + 29 * DP, TEXT)
    else:
        draw.line((x + 4 * DP, y + 14 * DP, x + 4 * DP, y + 44 * DP), fill=OUTLINE)
        icon(draw, "layers", x + 30 * DP, y + 29 * DP, TEXT)


def snackbar(draw, message, glyph="open"):
    f = font(13)
    tw = draw.textlength(message, font=f)
    w = tw + 74 * DP
    x = (W - w) / 2
    y = H - 10 * DP - 58 * DP - 8 * DP - 64 * DP - 60 * DP
    rounded(draw, (x, y, x + w, y + 44 * DP), int(22 * DP), fill=SURFACE_MAX, outline=OUTLINE)
    icon(draw, glyph, x + 24 * DP, y + 22 * DP, ACCENT, box=14)
    text(draw, (x + 44 * DP, y + 22 * DP), message, f, TEXT, "lm")


def phone(title):
    img = Image.new("RGB", (W, H), BACKGROUND)
    draw = ImageDraw.Draw(img)
    return img, draw, title


def frame(img, caption, subtitle):
    """A phone-shaped border plus a caption underneath."""
    out = Image.new("RGB", (img.width + 24 * DP, img.height + 90 * DP), (0x08, 0x09, 0x0B))
    d = ImageDraw.Draw(out)
    rounded(d, (6 * DP, 6 * DP, out.width - 6 * DP, out.height - 6 * DP), int(26 * DP), outline=(0x2A, 0x2E, 0x36), width=int(1.5 * DP))
    out.paste(img, (12 * DP, 30 * DP))
    d.text((out.width / 2, out.height - 44 * DP), caption, font=font(15, bold=True), fill=TEXT, anchor="mm")
    d.text((out.width / 2, out.height - 22 * DP), subtitle, font=font(11.5), fill=TEXT_DIM, anchor="mm")
    return out


# ---- screen 1: empty state -------------------------------------------------
img, draw, _ = phone("empty")
top_bar(draw, "LayerPad", "no document open", dirty=False)
# the empty card
cy = 300 * DP
rounded(draw, (26 * DP, cy - 90 * DP, 106 * DP, cy - 10 * DP), int(20 * DP), fill=SURFACE, outline=OUTLINE)
icon(draw, "layers", 66 * DP, cy - 50 * DP, ACCENT, box=30)
text(draw, (W / 2, cy + 30 * DP), "Start something", font(22, bold=True), TEXT, "mm")
text(draw, (W / 2, cy + 56 * DP), "Offline, private, and yours", font(13), TEXT_DIM, "mm")
card = (22 * DP, cy + 90 * DP, W - 22 * DP, cy + 90 * DP + 3 * 60 * DP)
rounded(draw, card, int(20 * DP), fill=SURFACE, outline=OUTLINE)
rows = [("open", "Open a PSD", "Layers, masks and text come along", ACCENT),
        ("new", "New document", "Blank canvas, pick a size", TEXT),
        ("image", "Import image", "Bring a photo in as its own layer", TEXT)]
for i, (g, name, sub, tint) in enumerate(rows):
    ry = card[1] + i * 60 * DP
    if i:
        draw.line((card[0] + 14 * DP, ry, card[2] - 14 * DP, ry), fill=OUTLINE, width=1)
    rounded(draw, (card[0] + 14 * DP, ry + 10 * DP, card[0] + 54 * DP, ry + 50 * DP), int(10 * DP),
            fill=ACCENT_SOFT if i == 0 else SURFACE_MAX)
    icon(draw, g, card[0] + 34 * DP, ry + 30 * DP, tint if i == 0 else TEXT, box=16)
    text(draw, (card[0] + 68 * DP, ry + 22 * DP), name, font(15, bold=True), TEXT)
    text(draw, (card[0] + 68 * DP, ry + 40 * DP), sub, font(12), TEXT_DIM)
text(draw, (W / 2, H - 60 * DP), "Fully offline: nothing ever leaves this phone", font(11.5), TEXT_FAINT, "mm")
screen_empty = frame(img, "Empty state", "three ways in, no dead ends")

# ---- screen 2: editing -----------------------------------------------------
img, draw, _ = phone("edit")
top_bar(draw, "poster.psd  •", "8000 × 6000 px  ·  opened at 1/2  ·  24%")
canvas_scene(draw, img)
options_strip(draw)
dock(draw, active="brush")
snackbar(draw, "Picked #FF9E3D")
screen_edit = frame(img, "Editing", "screen-sized compositing, floating chrome")

# ---- screen 3: layers sheet ------------------------------------------------
img, draw, _ = phone("layers")
top_bar(draw, "poster.psd  •", "8000 × 6000 px  ·  opened at 1/2  ·  24%")
canvas_scene(draw, img)
sheet_top = 260 * DP
draw.rectangle((0, sheet_top, W, H), fill=(0, 0, 0))
rounded(draw, (0, sheet_top, W, H), int(22 * DP), fill=SURFACE, outline=OUTLINE)
rounded(draw, (W / 2 - 18 * DP, sheet_top + 8 * DP, W / 2 + 18 * DP, sheet_top + 12 * DP), int(2 * DP), fill=(0x55, 0x58, 0x60))
text(draw, (18 * DP, sheet_top + 34 * DP), "Layers", font(17, bold=True), TEXT)
rounded(draw, (100 * DP, sheet_top + 28 * DP, 176 * DP, sheet_top + 52 * DP), int(12 * DP), fill=SURFACE_MAX)
text(draw, (138 * DP, sheet_top + 40 * DP), "4 layers", font(11.5, bold=True), TEXT_DIM, "mm")
icon_button(draw, "close", W - 60 * DP, sheet_top + 22 * DP, 44, TEXT_DIM)
# command strip
strip = ["image", "text", "duplicate", "add", "remove", "min", "flatten"]
x = 12 * DP
for i, g in enumerate(strip):
    icon(draw, "add" if g == "min" else ("trash" if g == "flatten" else g), x + 23 * DP, sheet_top + 84 * DP,
         DANGER if g == "flatten" else TEXT)
    x += 46 * DP
draw.line((12 * DP, sheet_top + 112 * DP, W - 12 * DP, sheet_top + 112 * DP), fill=OUTLINE)
# the footer of the sheet has its own backing; rows scroll underneath it
draw.rectangle((0, H - 130 * DP, W, H), fill=SURFACE)
# rows
layer_rows = [("Text · SUMMER", "Text · Helvetica-Bold", True, True),
              ("Group · effects", "Group · 2 layers", True, False),
              ("Photo", "4000×3000 · Overlay", True, False),
              ("Adjustment", "levels — kept as-is", True, False)]
ry = sheet_top + 124 * DP
for i, (name, sub, visible, selected) in enumerate(layer_rows):
    if selected:
        rounded(draw, (12 * DP, ry + 3 * DP, W - 12 * DP, ry + 63 * DP), int(14 * DP), fill=SURFACE_HIGH, outline=ACCENT_SOFT, width=2)
    checkerboard(img, (18 * DP, ry + 10 * DP, 62 * DP, ry + 54 * DP), cell=5)
    photo(img, (18 * DP, ry + 10 * DP, 62 * DP, ry + 54 * DP), label=False)
    rounded(draw, (18 * DP, ry + 10 * DP, 62 * DP, ry + 54 * DP), int(9 * DP), outline=OUTLINE)
    text(draw, (76 * DP, ry + 20 * DP), name, font(14, bold=True), TEXT if visible else TEXT_DIM)
    text(draw, (76 * DP, ry + 40 * DP), sub, font(11.5), TEXT_FAINT)
    icon(draw, "eye" if visible else "close", W - 42 * DP, ry + 32 * DP, TEXT if visible else TEXT_FAINT)
    ry += 66 * DP
# footer: blend + opacity
rounded(draw, (22 * DP, H - 118 * DP, 96 * DP, H - 94 * DP), int(12 * DP), fill=SURFACE_HIGH, outline=OUTLINE)
text(draw, (59 * DP, H - 106 * DP), "Normal", font(12), TEXT, "mm")
text(draw, (W - 30 * DP, H - 106 * DP), "100%", font(12, mono=True), TEXT, "ra")
rounded(draw, (22 * DP, H - 84 * DP, W - 22 * DP, H - 78 * DP), int(3 * DP), fill=SURFACE_MAX)
rounded(draw, (22 * DP, H - 84 * DP, W - 22 * DP, H - 78 * DP), int(3 * DP), fill=ACCENT)
draw.ellipse((W - 30 * DP, H - 90 * DP, W - 14 * DP, H - 74 * DP), fill=TEXT)
screen_layers = frame(img, "Layers", "sheet, not another screen")

# ---- compose ---------------------------------------------------------------
gap = 26 * DP
total_w = screen_empty.width + screen_edit.width + screen_layers.width + gap * 4
total_h = max(screen_empty.height, screen_edit.height, screen_layers.height) + gap * 2 + 70 * DP
sheet = Image.new("RGB", (total_w, total_h), (0x05, 0x06, 0x08))
sd = ImageDraw.Draw(sheet)
sd.text((gap, 34 * DP), "LayerPad — interface redesign", font=font(26, bold=True), fill=TEXT)
sd.text((gap, 66 * DP), "Rendered from the same measurements the app uses (3x density, 360 dp wide)",
        font=font(12.5), fill=TEXT_DIM)
x = gap
for screen in (screen_empty, screen_edit, screen_layers):
    sheet.paste(screen, (int(x), int(gap + 80 * DP)))
    x += screen.width + gap
sheet = sheet.resize((int(sheet.width / 2.2), int(sheet.height / 2.2)), Image.LANCZOS)
sheet.save("/home/user/photo_cc/docs/ui-redesign.png", quality=95)
print("wrote docs/ui-redesign.png", sheet.size)
