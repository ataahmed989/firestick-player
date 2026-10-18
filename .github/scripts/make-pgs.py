#!/usr/bin/env python3
"""Writes a Blu-ray picture subtitle file (PGS, .sup) for the screenshot run's test film.

A two-line subtitle every 4 seconds for 5 minutes, at the same times as the SRT in make-test-video.sh
(shown from t+0.5 s to t+4.3 s). Each one is drawn the way a real disc's are: yellow-white bold text with a
black outline on a 1920x1080 plane, cropped to the text and placed bottom-centre, using a small palette.

Usage: make-pgs.py OUT.sup
"""
import itertools
import os
import struct
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

PLANE_W, PLANE_H = 1920, 1080
FONT_SIZE = 56
OUTLINE = 4                     # px of black around the letters
LINE_GAP = 14                   # px between the two lines
BOTTOM_MARGIN = 64              # px from the bottom of the plane to the bottom of the picture
FILL = (255, 247, 205)          # light yellow-white
LINE_1 = "This picture subtitle is now text."
LINE_2 = "Shown in your own style. ({})"   # the subtitle's number, so OCR results can be told apart

# Palette: index 0 is transparent; the others are the edge (black at a few strengths) and the
# blend from black to the fill colour across the inside edge of the outline.
ALPHA_STEPS = 4
FILL_STEPS = 8

# Segment types.
PDS, ODS, PCS, WDS, END = 0x14, 0x15, 0x16, 0x17, 0x80
EPOCH_START, NORMAL = 0x80, 0x00
MAX_SEGMENT = 0xFFFF            # a segment's size is a 16-bit field


def load_font():
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, FONT_SIZE)
    try:
        return ImageFont.load_default(size=FONT_SIZE)    # Pillow 10.1 and later
    except TypeError:
        return ImageFont.load_default()


def text_masks(lines, font):
    """Coverage of the whole subtitle (letters plus outline) and of the letters alone, cropped to the text."""
    probe = ImageDraw.Draw(Image.new("L", (1, 1)))

    def bbox(line):
        try:
            return probe.textbbox((0, 0), line, font=font, stroke_width=OUTLINE)
        except (TypeError, ValueError):   # a bitmap fallback font can't stroke
            l, t, r, b = probe.textbbox((0, 0), line, font=font)
            return l - OUTLINE, t - OUTLINE, r + OUTLINE, b + OUTLINE

    boxes = [bbox(line) for line in lines]
    widths = [b[2] - b[0] for b in boxes]
    heights = [b[3] - b[1] for b in boxes]
    pad = 2
    w = max(widths) + 2 * pad
    h = sum(heights) + LINE_GAP * (len(lines) - 1) + 2 * pad
    outer, inner = Image.new("L", (w, h), 0), Image.new("L", (w, h), 0)
    y = pad
    for line, box, lw, lh in zip(lines, boxes, widths, heights):
        # Each line centred; textbbox's offset is taken off so the ink starts where we want it.
        at = ((w - lw) // 2 - box[0], y - box[1])
        try:
            ImageDraw.Draw(outer).text(at, line, font=font, fill=255, stroke_width=OUTLINE, stroke_fill=255)
        except (TypeError, ValueError, OSError):
            # A bitmap fallback font can't stroke: draw it shifted all round instead.
            for dx, dy in itertools.product(range(-OUTLINE, OUTLINE + 1), repeat=2):
                if dx * dx + dy * dy <= OUTLINE * OUTLINE:
                    ImageDraw.Draw(outer).text((at[0] + dx, at[1] + dy), line, font=font, fill=255)
        ImageDraw.Draw(inner).text(at, line, font=font, fill=255)
        y += lh + LINE_GAP
    # Crop to the ink; PGS objects must be at least 8x8.
    left, top, right, bottom = outer.getbbox()
    right, bottom = max(right, left + 8), max(bottom, top + 8)
    return outer.crop((left, top, right, bottom)), inner.crop((left, top, right, bottom))


def ycrcb(rgb):
    """BT.709 limited range, as on Blu-ray."""
    r, g, b = (c / 255 for c in rgb)
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    cb, cr = (b - y) / 1.8556, (r - y) / 1.5748
    clamp = lambda v: max(0, min(255, round(v)))
    return clamp(16 + 219 * y), clamp(128 + 224 * cr), clamp(128 + 224 * cb)


def quantise(outer, inner):
    """Turns the two masks into palette indices (index 0 transparent) and the palette itself."""
    alpha_level = lambda v: 0 if v < 32 else max(1, round(v * ALPHA_STEPS / 255))
    fill_level = lambda v: round(v * (FILL_STEPS - 1) / 255)
    # One code per (edge strength, fill blend) pair; codes with no edge strength are see-through.
    a = outer.point([alpha_level(v) * FILL_STEPS for v in range(256)])
    f = inner.point([fill_level(v) for v in range(256)])
    codes = ImageChops.add(a, f)
    used = sorted(c for _, c in codes.getcolors(256) if c >= FILL_STEPS)
    palette, lut = [(0, 16, 128, 128, 0)], [0] * 256
    for index, code in enumerate(used, start=1):
        t = (code % FILL_STEPS) / (FILL_STEPS - 1)
        rgb = tuple(round(c * t) for c in FILL)                    # black → fill
        alpha = round((code // FILL_STEPS) * 255 / ALPHA_STEPS)
        palette.append((index, *ycrcb(rgb), alpha))
        lut[code] = index
    return codes.point(lut), palette


def rle(indices):
    """PGS run-length encoding, a row at a time, each row ending in 00 00."""
    out = bytearray()
    w, data = indices.size[0], indices.tobytes()
    for row in range(indices.size[1]):
        for colour, run in itertools.groupby(data[row * w:(row + 1) * w]):
            n = len(list(run))
            while n:
                k = min(n, 0x3FFF)
                n -= k
                if colour and k < 3:
                    out += bytes([colour]) * k                     # one byte a pixel
                elif colour == 0:
                    out += bytes([0, k]) if k < 64 else bytes([0, 0x40 | k >> 8, k & 0xFF])
                else:
                    out += bytes([0, 0x80 | k, colour]) if k < 64 else bytes([0, 0xC0 | k >> 8, k & 0xFF, colour])
        out += b"\x00\x00"
    return bytes(out)


def segment(kind, payload, pts, dts):
    """'PG', 90 kHz PTS and DTS, type, size, payload."""
    return struct.pack(">2sIIBH", b"PG", pts & 0xFFFFFFFF, dts & 0xFFFFFFFF, kind, len(payload)) + payload


def pcs(number, state, objects):
    """Presentation composition: the plane, then (object id, window id, x, y) for each object shown."""
    body = struct.pack(">HHBHBBBB", PLANE_W, PLANE_H, 0x10, number & 0xFFFF, state, 0, 0, len(objects))
    for obj, win, x, y in objects:
        body += struct.pack(">HBBHH", obj, win, 0x00, x, y)      # 0x00: not cropped
    return body


def wds(x, y, w, h):
    return struct.pack(">BBHHHH", 1, 0, x, y, w, h)


def pds(palette):
    return struct.pack(">BB", 0, 0) + b"".join(struct.pack(">BBBBB", *entry) for entry in palette)


def ods_pieces(w, h, data):
    """The picture, split into as many segments as it needs; the first one carries the size."""
    first_room = MAX_SEGMENT - 11
    rest_room = MAX_SEGMENT - 4
    chunks = [data[:first_room]]
    chunks += [data[i:i + rest_room] for i in range(first_room, len(data), rest_room)]
    pieces = []
    for i, chunk in enumerate(chunks):
        flags = (0x80 if i == 0 else 0) | (0x40 if i == len(chunks) - 1 else 0)
        head = struct.pack(">HBB", 0, 0, flags)
        if i == 0:
            # Object data length counts the 4 bytes of width and height too.
            head += struct.pack(">I", len(data) + 4)[1:] + struct.pack(">HH", w, h)
        pieces.append(head + chunk)
    return pieces


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip().splitlines()[-1])
    font = load_font()
    out = bytearray()
    number = 0
    for i in range(75):
        start, end = round((4 * i + 0.5) * 90000), round((4 * i + 4.3) * 90000)
        indices, palette = quantise(*text_masks([LINE_1, LINE_2.format(i + 1)], font))
        w, h = indices.size
        x, y = (PLANE_W - w) // 2, PLANE_H - BOTTOM_MARGIN - h
        data = rle(indices)
        # Decoding starts early enough to be done on time (DTS ≤ PTS, as on discs): the plane is cleared,
        # then the picture decoded, and it's shown at `start`.
        clear = 90000 * PLANE_W * PLANE_H // 32_000_000 + 1         # 32 MB/s plane clearing
        decode = 90000 * w * h // 16_000_000 + 1                   # 16 MB/s object decoding
        dts = start - clear - decode
        # Show: PCS (epoch start), WDS, PDS, ODS, END.
        out += segment(PCS, pcs(number, EPOCH_START, [(0, 0, x, y)]), start, dts)
        out += segment(WDS, wds(x, y, w, h), dts + clear, dts)
        out += segment(PDS, pds(palette), dts, dts)
        for piece in ods_pieces(w, h, data):
            out += segment(ODS, piece, start, dts + clear)
        out += segment(END, b"", start, start)
        number += 1
        # Clear: PCS with no objects, the same window, END.
        out += segment(PCS, pcs(number, NORMAL, []), end, end - clear)
        out += segment(WDS, wds(x, y, w, h), end - clear, end - clear)
        out += segment(END, b"", end - clear, end - clear)
        number += 1
    with open(sys.argv[1], "wb") as f:
        f.write(out)


if __name__ == "__main__":
    main()
