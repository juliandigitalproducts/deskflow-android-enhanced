#!/usr/bin/env python3
"""
Builds Google Play tablet screenshots (7" and 10") and README presentation collages.
Usage: python3 scripts/generate-screenshots.py
"""

import os
import glob
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENSHOTS_DIR = os.path.join(ROOT, "docs", "assets", "screenshots")
METADATA_BASE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images")

W, H = 1832, 800

def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), radius, fill=255)
    im.putalpha(mask)
    return im

def shadow(canvas, box, radius, dark):
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(sh)
    x0, y0, x1, y1 = box
    col = (0, 0, 0, 150 if dark else 70)
    d.rounded_rectangle((x0 + 6, y0 + 12, x1 + 6, y1 + 12), radius, fill=col)
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(16)))

def background(dark=True):
    bg = Image.new("RGBA", (W, H), (10, 14, 23, 255) if dark else (236, 240, 245, 255))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # Soft indigo glow matching the app logo (art/brand/)
    ImageDraw.Draw(glow).ellipse((W * 0.15, -H * 0.3, W * 0.85, H * 0.9), fill=(74, 35, 112, 90 if dark else 40))
    bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(120)))
    return bg

def make_tablet_collage(img_paths, out_path):
    canvas = background(True)
    margin, gap = 44, 32
    n = len(img_paths)
    tw = (W - 2 * margin - gap * (n - 1)) // n
    w0, h0 = Image.open(img_paths[0]).size
    th = int(tw * h0 / w0)
    y = (H - th) // 2
    x = margin
    for p in img_paths:
        im = Image.open(p).convert("RGBA").resize((tw, th), Image.LANCZOS)
        r = int(tw * 0.025)
        shadow(canvas, (x, y, x + tw, y + th), r, True)
        canvas.alpha_composite(rounded(im, r), (x, y))
        border = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
        ImageDraw.Draw(border).rounded_rectangle((0, 0, tw - 1, th - 1), r, outline=(255, 255, 255, 35), width=2)
        canvas.alpha_composite(border, (x, y))
        x += tw + gap
    top = max(0, y - 35)
    bottom = min(H, y + th + 35)
    canvas.crop((0, top, W, bottom)).convert("RGB").save(out_path, optimize=True)
    print(f"Generated collage: {out_path}")

def generate_play_screenshots():
    order = [
        "screenshot_tablet_home.png",
        "screenshot_tablet_default.png",
        "screenshot_tablet_config.png",
        "screenshot_keyboard_ime_active.png",
    ]

    # 7-inch tablet screenshots (1920x1200, 16:10)
    seven_dir = os.path.join(METADATA_BASE, "sevenInchScreenshots")
    os.makedirs(seven_dir, exist_ok=True)
    for i, name in enumerate(order, 1):
        src = os.path.join(SCREENSHOTS_DIR, name)
        im = Image.open(src).convert("RGB")
        im.resize((1920, 1200), Image.LANCZOS).save(os.path.join(seven_dir, f"{i}.png"), optimize=True)
    print(f"Generated 7-inch screenshots in {seven_dir}")

    # 10-inch tablet screenshots (2560x1600, 16:10)
    ten_dir = os.path.join(METADATA_BASE, "tenInchScreenshots")
    os.makedirs(ten_dir, exist_ok=True)
    for f in glob.glob(os.path.join(ten_dir, "screenshot_*.png")):
        os.remove(f)
    for i, name in enumerate(order, 1):
        src = os.path.join(SCREENSHOTS_DIR, name)
        im = Image.open(src).convert("RGB")
        im.resize((2560, 1600), Image.LANCZOS).save(os.path.join(ten_dir, f"{i}.png"), optimize=True)
    print(f"Generated 10-inch screenshots in {ten_dir}")

    # Clean phoneScreenshots if exists
    phone_dir = os.path.join(METADATA_BASE, "phoneScreenshots")
    if os.path.exists(phone_dir):
        for f in glob.glob(os.path.join(phone_dir, "*.png")):
            os.remove(f)
        try:
            os.rmdir(phone_dir)
        except OSError:
            pass
        print("Cleaned phoneScreenshots directory.")

def main():
    print("Generating README collages...")
    make_tablet_collage([
        os.path.join(SCREENSHOTS_DIR, "screenshot_tablet_home.png"),
        os.path.join(SCREENSHOTS_DIR, "screenshot_tablet_default.png"),
    ], os.path.join(SCREENSHOTS_DIR, "deskflow-tablet-main.png"))

    make_tablet_collage([
        os.path.join(SCREENSHOTS_DIR, "screenshot_tablet_config.png"),
        os.path.join(SCREENSHOTS_DIR, "screenshot_keyboard_ime_active.png"),
    ], os.path.join(SCREENSHOTS_DIR, "deskflow-tablet-settings.png"))

    print("\nGenerating Fastlane Play Store tablet metadata...")
    generate_play_screenshots()
    print("\nDone!")

if __name__ == "__main__":
    main()
