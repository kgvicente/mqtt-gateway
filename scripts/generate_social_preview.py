#!/usr/bin/env python3
"""
Generate Social Preview card with EXPANDED BACKGROUND (extra bleed / margins):
- Allows flexible scaling, cropping, and positioning in card templates without clipping edges
- Keeps the exact content, typography, badges, and browser window intact
- Seamlessly extends the dark gradient, 40px grid, and ambient radial glows
"""
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path
import shutil

S = 2  # 2x supersampling

def render_card(total_w, total_h, out_filename):
    orig_w, orig_h = 1280, 640
    offset_x = (total_w - orig_w) // 2
    offset_y = (total_h - orig_h) // 2

    # Canvas
    im = Image.new("RGBA", (total_w * S, total_h * S), (11, 17, 28, 255))
    d = ImageDraw.Draw(im)

    # 1. Background vertical gradient across total canvas
    for y in range(total_h * S):
        prog = y / (total_h * S)
        r = int(11 + (18 - 11) * prog)
        g = int(17 + (28 - 17) * prog)
        b = int(28 + (46 - 28) * prog)
        d.line([(0, y), (total_w * S, y)], fill=(r, g, b, 255))

    # Ambient radial glows
    glow = Image.new("RGBA", (total_w * S, total_h * S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    glow1_cx = (offset_x + 60) * S
    glow1_cy = (offset_y + 120) * S
    for rad in range(450 * S, 0, -6 * S):
        alpha = int(25 * (1 - rad / (450 * S)))
        gd.ellipse([glow1_cx - rad, glow1_cy - rad, glow1_cx + rad, glow1_cy + rad], fill=(14, 165, 233, alpha))

    glow2_cx = (offset_x + 980) * S
    glow2_cy = (offset_y + 320) * S
    for rad in range(500 * S, 0, -6 * S):
        alpha = int(28 * (1 - rad / (500 * S)))
        gd.ellipse([glow2_cx - rad, glow2_cy - rad, glow2_cx + rad, glow2_cy + rad], fill=(99, 102, 241, alpha))

    im = Image.alpha_composite(im, glow)
    d = ImageDraw.Draw(im)

    # Seamless 40px grid across whole expanded canvas
    grid = Image.new("RGBA", (total_w * S, total_h * S), (0, 0, 0, 0))
    gridd = ImageDraw.Draw(grid)
    for x in range(0, total_w * S, 40 * S):
        gridd.line([(x, 0), (x, total_h * S)], fill=(255, 255, 255, 7))
    for y in range(0, total_h * S, 40 * S):
        gridd.line([(0, y), (total_w * S, y)], fill=(255, 255, 255, 7))
    im = Image.alpha_composite(im, grid)
    d = ImageDraw.Draw(im)

    # Fonts
    font_dir = Path("C:/Windows/Fonts")
    def get_font(size, bold=False):
        fname = "segoeuib.ttf" if bold else "segoeui.ttf"
        return ImageFont.truetype(str(font_dir / fname), size * S)

    f_tag = get_font(13, bold=True)
    f_title = get_font(54, bold=True)
    f_sub1 = get_font(27, bold=True)
    f_badge = get_font(14, bold=True)
    f_bullet = get_font(15, bold=False)
    f_footer = get_font(15, bold=False)
    f_footer_bold = get_font(15, bold=True)
    f_urlbar = get_font(12, bold=False)

    white = (245, 248, 252, 255)
    pure_white = (255, 255, 255, 255)
    teal = (40, 223, 204, 255)
    muted = (167, 185, 203, 255)

    LEFT_X = (offset_x + 64) * S

    # 2. Isometric 3D Cube Logo & Aligned Tag
    cube_cx = LEFT_X + 13 * S
    cube_cy = (offset_y + 66) * S
    cube_r = 14 * S

    cos30 = math.cos(math.radians(30))
    sin30 = math.sin(math.radians(30))

    C = (cube_cx, cube_cy)
    T = (cube_cx, cube_cy - cube_r)
    TR = (cube_cx + cube_r * cos30, cube_cy - cube_r * sin30)
    BR = (cube_cx + cube_r * cos30, cube_cy + cube_r * sin30)
    B = (cube_cx, cube_cy + cube_r)
    BL = (cube_cx - cube_r * cos30, cube_cy + cube_r * sin30)
    TL = (cube_cx - cube_r * cos30, cube_cy - cube_r * sin30)

    # Shaded 3D faces
    d.polygon([T, TR, C, TL], fill=(40, 223, 204, 240))
    d.polygon([TL, C, B, BL], fill=(22, 160, 145, 240))
    d.polygon([C, TR, BR, B], fill=(15, 115, 105, 240))

    # Edges
    edge_col = (56, 248, 228, 255)
    d.line([T, TR, BR, B, BL, TL, T], fill=edge_col, width=int(1.5 * S))
    d.line([C, T], fill=edge_col, width=int(1.5 * S))
    d.line([C, BL], fill=edge_col, width=int(1.5 * S))
    d.line([C, BR], fill=edge_col, width=int(1.5 * S))

    # Tag text vertically aligned
    tag_x = TR[0] + 16 * S
    d.text((tag_x, cube_cy - int(1.5 * S)), "OPEN SOURCE  /  MQTT INFRASTRUCTURE", font=f_tag, fill=muted, anchor="lm")

    # 3. Title & Subtitle
    d.text((LEFT_X, (offset_y + 114) * S), "MQTT Gateway", font=f_title, fill=white, anchor="lt")
    d.text((LEFT_X, (offset_y + 188) * S), "Multi-broker Auth & ACL", font=f_sub1, fill=teal, anchor="lt")

    # 4. Badges in 2 Lines
    cap_bbox = d.textbbox((0, 0), "M", font=f_badge)
    cap_mid_y = (cap_bbox[1] + cap_bbox[3]) / 2

    pill_h = 34 * S
    pill_r = 7 * S

    line1_items = [
        ("Mosquitto", (5, 150, 105)),
        ("EMQX", (37, 99, 235)),
        ("VerneMQ", (124, 58, 237)),
    ]
    line2_items = [
        ("Zero DB", (217, 119, 6)),
        ("Web UI", (2, 132, 199)),
    ]

    def render_pill_row(items, start_x, start_y):
        x = start_x
        for label, col in items:
            t_bbox = d.textbbox((0, 0), label, font=f_badge)
            t_w = t_bbox[2] - t_bbox[0]
            pill_w = t_w + (24 * S)

            d.rounded_rectangle([x, start_y, x + pill_w, start_y + pill_h], radius=pill_r,
                                fill=(*col, 255), outline=(*col, 255), width=1*S)

            box_cx = x + pill_w / 2
            box_cy = start_y + pill_h / 2
            text_x = box_cx - (t_bbox[0] + t_bbox[2]) / 2
            text_y = box_cy - cap_mid_y + (3.5 * S)

            d.text((text_x, text_y), label, font=f_badge, fill=pure_white, anchor="lt")
            x += pill_w + (11 * S)

    render_pill_row(line1_items, LEFT_X, (offset_y + 246) * S)
    render_pill_row(line2_items, LEFT_X, (offset_y + 293) * S)

    # 5. Bullets
    bullet_y = (offset_y + 354) * S
    bullets = [
        "• Zero database required — lightweight local state",
        "• Single unified HTTP authentication & ACL policy",
        "• Real-time web admin panel & incident monitoring"
    ]
    for b_text in bullets:
        d.text((LEFT_X, bullet_y), b_text, font=f_bullet, fill=(185, 202, 222, 255), anchor="lt")
        bullet_y += 28 * S

    # 6. Right Browser Window Mockup
    WIN_W, WIN_H = 650 * S, 440 * S
    WIN_X, WIN_Y = (offset_x + 580) * S, (offset_y + 75) * S

    shadow = Image.new("RGBA", (total_w * S, total_h * S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle([WIN_X - 6*S, WIN_Y + 6*S, WIN_X + WIN_W + 6*S, WIN_Y + WIN_H + 16*S],
                         radius=16*S, fill=(0, 0, 0, 175))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18 * S))
    im = Image.alpha_composite(im, shadow)
    d = ImageDraw.Draw(im)

    window = Image.new("RGBA", (WIN_W, WIN_H), (15, 23, 42, 255))
    wdraw = ImageDraw.Draw(window)

    header_h = 36 * S
    wdraw.rectangle([0, 0, WIN_W, header_h], fill=(30, 41, 59, 255))
    wdraw.ellipse([14*S, 13*S, 24*S, 23*S], fill=(239, 68, 68, 255))
    wdraw.ellipse([32*S, 13*S, 42*S, 23*S], fill=(245, 158, 11, 255))
    wdraw.ellipse([50*S, 13*S, 60*S, 23*S], fill=(34, 197, 94, 255))

    wdraw.rounded_rectangle([75*S, 8*S, WIN_W - 20*S, 28*S], radius=6*S,
                            fill=(15, 23, 42, 220), outline=(51, 65, 85, 255), width=1*S)
    wdraw.text((88*S, 10*S), "http://127.0.0.1:18085/gateway/", font=f_urlbar, fill=(148, 163, 184, 255))

    dash_img = Image.open("docs/images/dashboard.png").convert("RGBA")
    inner_w = WIN_W
    inner_h = WIN_H - header_h
    dash_resized = dash_img.resize((inner_w, int(inner_w * dash_img.height / dash_img.width)), Image.Resampling.LANCZOS)
    dash_cropped = dash_resized.crop((0, 0, inner_w, inner_h))
    window.paste(dash_cropped, (0, header_h))

    win_mask = Image.new("L", (WIN_W, WIN_H), 0)
    ImageDraw.Draw(win_mask).rounded_rectangle([0, 0, WIN_W, WIN_H], radius=14*S, fill=255)

    im.paste(window, (WIN_X, WIN_Y), win_mask)
    d = ImageDraw.Draw(im)

    # 7. Footer
    FOOTER_Y = (offset_y + 575) * S
    CONTENT_RIGHT_X = (offset_x + 1216) * S
    d.line([(LEFT_X, FOOTER_Y), (CONTENT_RIGHT_X, FOOTER_Y)], fill=(41, 65, 79, 255), width=1*S)

    d.text((LEFT_X, FOOTER_Y + 18*S), "github.com/kgvicente/mqtt-gateway", font=f_footer, fill=muted, anchor="lt")
    d.text((CONTENT_RIGHT_X, FOOTER_Y + 18*S), "AGPL-3.0  •  GNU Affero GPL", font=f_footer_bold, fill=muted, anchor="rt")

    # Downsample
    final_img = im.resize((total_w, total_h), Image.Resampling.LANCZOS).convert("RGB")

    out_file = Path(f"docs/images/{out_filename}")
    out_file.parent.mkdir(parents=True, exist_ok=True)
    final_img.save(out_file, "PNG", optimize=True)

    desktop_file = Path(f"C:/Users/ingvi/OneDrive/Escritorio/{out_filename}")
    desktop_file.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(out_file, desktop_file)

    kb = out_file.stat().st_size / 1024
    print(f"OK: Generated {out_file} & {desktop_file}: {total_w}x{total_h}, {kb:.1f} KB")

if __name__ == "__main__":
    # 1. Standard Full HD (1920x1080) - Most versatile for 16:9 card templates
    render_card(1920, 1080, "social-preview-1920x1080.png")
    
    # 2. Expanded 2:1 ratio (1600x800) - Extra margin on all sides for 2:1 crop templates
    render_card(1600, 800, "social-preview-1600x800.png")

    # 3. Also copy the 1920x1080 directly to social-preview.png on Desktop for immediate drag-and-drop
    shutil.copy2("C:/Users/ingvi/OneDrive/Escritorio/social-preview-1920x1080.png",
                 "C:/Users/ingvi/OneDrive/Escritorio/social-preview.png")
    print("Updated social-preview.png on Desktop with expanded background.")
