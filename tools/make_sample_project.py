#!/usr/bin/env python3
"""
Generates the bundled sample project `neon_warrior.mwproj` (format v1, see docs/PROJECT_FORMAT.md).

This is a developer tool. It is NOT part of the Android app and is never run on the phone.
Requires Pillow (MIT-CMU / HPND license): pip install pillow

Usage:  python3 tools/make_sample_project.py [output.mwproj]
"""
import json
import math
import os
import random
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFilter, ImageChops

W, H = 720, 1280
OUT_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "samples", "neon_warrior.mwproj")


def gradient(size, top, bottom):
    img = Image.new("RGB", size)
    d = ImageDraw.Draw(img)
    for y in range(size[1]):
        t = y / (size[1] - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (size[0], y)], fill=c)
    return img


def mountain_polygon(seed):
    rnd = random.Random(seed)
    pts = [(0, H)]
    x = 0
    while x <= W + 40:
        y = 930 + 60 * math.sin(x / 90.0) + rnd.uniform(-25, 25)
        pts.append((x, y))
        x += 40
    pts.append((W, H))
    return pts


def build_background():
    bg = gradient((W, H), (8, 6, 26), (46, 12, 70)).convert("RGBA")
    rnd = random.Random(7)
    stars = ImageDraw.Draw(bg)
    for _ in range(260):
        x, y = rnd.randrange(W), rnd.randrange(0, 820)
        b = rnd.randrange(120, 255)
        stars.point((x, y), fill=(b, b, 255, 255))
    # Moon with soft halo.
    halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(halo).ellipse((540 - 150, 250 - 150, 540 + 150, 250 + 150), fill=(120, 80, 255, 150))
    halo = halo.filter(ImageFilter.GaussianBlur(60))
    bg = Image.alpha_composite(bg, halo)
    ImageDraw.Draw(bg).ellipse((540 - 80, 250 - 80, 540 + 80, 250 + 80), fill=(214, 226, 255, 255))
    # Mountains, then a neon grid floor.
    ImageDraw.Draw(bg).polygon(mountain_polygon(3), fill=(24, 10, 46, 255))
    floor = ImageDraw.Draw(bg)
    floor.polygon([(0, 1000), (W, 1000), (W, H), (0, H)], fill=(14, 6, 30, 255))
    vx, vy = W / 2, 1000
    for i in range(-10, 11):
        floor.line([(vx, vy), (vx + i * 160, H)], fill=(255, 43, 214, 110), width=2)
    for k in range(1, 9):
        y = 1000 + (H - 1000) * (k / 8.0) ** 2
        floor.line([(0, y), (W, y)], fill=(0, 229, 255, 90), width=2)
    return bg.convert("RGB")


def build_depth():
    d = gradient((W, H), (30, 30, 30), (60, 60, 60)).convert("L")
    dd = ImageDraw.Draw(d)
    dd.polygon(mountain_polygon(3), fill=100)
    floor = Image.new("L", (W, H), 0)
    fd = ImageDraw.Draw(floor)
    for y in range(1000, H):
        t = (y - 1000) / (H - 1000)
        fd.line([(0, y), (W, y)], fill=int(150 + 50 * t))
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rectangle((0, 1000, W, H), fill=255)
    d.paste(floor, (0, 0), mask)
    return d


def shape_layer(draw_fn, color):
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer), color)
    return layer


def crop_to_alpha(img):
    bbox = img.getchannel("A").getbbox()
    return bbox


def make_layers():
    layers = {}

    def cape(d, c):
        d.polygon([(300, 500), (420, 500), (470, 700), (520, 960), (440, 1000), (380, 920),
                   (330, 1010), (250, 930), (270, 700)], fill=c)
        d.line([(300, 510), (250, 920)], fill=(255, 43, 214, 255), width=3)
    layers["cape"] = shape_layer(cape, (42, 15, 63, 255))

    def body(d, c):
        d.ellipse((316, 386, 404, 474), fill=c)                        # head
        d.rectangle((345, 466, 375, 494), fill=c)                      # neck
        d.polygon([(300, 490), (420, 490), (440, 640), (410, 760), (310, 760), (280, 640)], fill=c)
        d.ellipse((260, 478, 320, 530), fill=c)                        # pauldrons
        d.ellipse((400, 478, 460, 530), fill=c)
        d.polygon([(282, 520), (246, 640), (262, 700), (290, 650), (300, 560)], fill=c)   # left arm
        d.polygon([(428, 520), (470, 600), (486, 648), (458, 660), (420, 600)], fill=c)   # right arm
        d.polygon([(318, 760), (300, 1000), (282, 1150), (330, 1150), (346, 1000), (352, 760)], fill=c)
        d.polygon([(372, 760), (380, 1000), (398, 1150), (440, 1150), (420, 1000), (400, 760)], fill=c)
        d.rectangle((300, 700, 420, 722), fill=(0, 229, 255, 255))     # neon belt
        d.line([(300, 500), (310, 740), (340, 760)], fill=(0, 229, 255, 255), width=3)
        d.line([(420, 500), (410, 740), (380, 760)], fill=(0, 229, 255, 255), width=3)
    layers["body"] = shape_layer(body, (28, 39, 64, 255))

    def hair(d, c):
        d.polygon([(310, 420), (316, 370), (360, 340), (404, 370), (410, 420), (430, 520), (400, 500),
                   (380, 580), (360, 510), (340, 590), (320, 500), (300, 470)], fill=c)
    layers["hair"] = shape_layer(hair, (155, 93, 229, 255))

    def sword(d, c):
        d.polygon([(478, 650), (492, 640), (612, 300), (604, 296)], fill=c)     # blade
        d.polygon([(462, 640), (506, 652), (496, 668), (456, 654)], fill=(120, 90, 40, 255))  # guard
        d.polygon([(470, 650), (486, 656), (478, 690), (462, 684)], fill=(60, 40, 30, 255))   # grip
    layers["sword"] = shape_layer(sword, (217, 247, 255, 255))
    return layers


def build_sword_glow():
    g = Image.new("L", (W, H), 0)
    ImageDraw.Draw(g).line([(492, 640), (606, 298)], fill=255, width=4)
    return g


def crop_pack(rgba, bbox):
    return rgba.crop(bbox)


def main():
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else OUT_DEFAULT)
    os.makedirs(os.path.dirname(out), exist_ok=True)

    bg = build_background()
    depth = build_depth()
    layer_defs = [
        # id, role, depth, rig (or None), has mask
        ("cape", "cape", 0.25, {"mode": "ripple", "amplitude": 0.018, "frequency": 0.35, "phase": 0.0,
                                "pivot": [0.5, 0.0], "direction": [1, 0]}),
        ("body", "body", 0.5, None),
        ("hair", "hair", 0.7, {"mode": "sway", "amplitude": 0.012, "frequency": 0.5, "phase": 0.6,
                               "pivot": [0.5, 0.0], "direction": [1, 0.15]}),
        ("sword", "sword", 0.85, {"mode": "sway", "amplitude": 0.006, "frequency": 0.3, "phase": 1.2,
                                  "pivot": [0.55, 0.85], "direction": [0.3, 1]}),
    ]
    raw = make_layers()
    glow_full = build_sword_glow()

    manifest_layers = []
    files = {}
    composite = bg.convert("RGBA")
    for lid, role, depth_v, rig in layer_defs:
        rgba = raw[lid]
        bbox = crop_to_alpha(rgba)
        if bbox is None:
            raise SystemExit(f"layer {lid} is empty")
        bbox = (max(0, bbox[0] - 4), max(0, bbox[1] - 4), min(W, bbox[2] + 4), min(H, bbox[3] + 4))
        crop = crop_pack(rgba, bbox)
        entry = {
            "id": lid,
            "role": role,
            "file": f"layers/{lid}.png",
            "x": bbox[0],
            "y": bbox[1],
            "depth": depth_v,
        }
        files[f"layers/{lid}.png"] = crop
        if rig is not None:
            # Mask = the layer's own silhouette, so only the moving part sways.
            mask = crop.getchannel("A")
            files[f"masks/{lid}.png"] = mask
            entry["mask"] = f"masks/{lid}.png"
            entry["rig"] = rig
        if lid == "sword":
            gcrop = glow_full.crop(bbox)
            gcrop = gcrop.filter(ImageFilter.GaussianBlur(3))
            files["glow/sword_glow.png"] = gcrop
            entry["glowMask"] = "glow/sword_glow.png"
        manifest_layers.append(entry)
        composite = Image.alpha_composite(composite, crop_full(crop, bbox))

    preview = composite.convert("RGB").resize((360, 640), Image.LANCZOS)

    manifest = {
        "format": "mwproj",
        "formatVersion": 1,
        "name": "Neon Warrior",
        "canvas": {"width": W, "height": H},
        "preview": "preview.png",
        "background": {"file": "background.png", "depth": "depth.png"},
        "layers": manifest_layers,
        "particles": [
            {"type": "ambient", "count": 60, "region": [0, 0, 1, 1], "color": "#CCE6FF"},
            {"type": "magic", "count": 24, "region": [0.12, 0.18, 0.76, 0.5], "color": "#9B5DE5"},
            {"type": "fire", "count": 70, "region": [0, 0.74, 1, 0.26], "color": "#FF7A1A"},
            {"type": "sparks", "count": 30, "region": [0.6, 0.2, 0.3, 0.35], "color": "#FFD27A"},
        ],
        "effects": {
            "outerGlowEnabled": True,
            "outerGlowColor": "#00E5FF",
            "outerGlowIntensity": 0.8,
            "outerGlowRadius": 0.018,
            "innerGlowEnabled": True,
            "innerGlowColor": "#FF2BD6",
            "innerGlowIntensity": 0.35,
            "glowPulseSpeed": 0.8,
            "bgBlur": 0.05,
            "bgContrast": 1.05,
        },
        "motion": {
            "strength": 1.0,
            "perspective": 0.5,
            "depthScale": 1.0,
            "smoothing": 6.0,
            "motionLimit": 0.6,
            "idleAmount": 0.2,
        },
    }

    all_files = {
        "manifest.json": json.dumps(manifest, indent=2).encode("utf-8"),
        "preview.png": png_bytes(preview),
        "background.png": png_bytes(bg),
        "depth.png": png_bytes(depth),
    }
    for name, img in files.items():
        all_files[name] = png_bytes(img)

    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in ["manifest.json", "preview.png", "background.png", "depth.png"] + sorted(files.keys()):
            data = all_files[name]
            z.writestr(name, data)
    print(f"wrote {out} ({os.path.getsize(out)} bytes, {len(all_files)} entries)")


def crop_full(crop, bbox):
    full = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    full.paste(crop, (bbox[0], bbox[1]))
    return full


def png_bytes(img):
    import io
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


if __name__ == "__main__":
    main()
