#!/usr/bin/env python3
"""Convert Surfing SVG icons to Android Vector Drawable XML files."""

import os
import re
import xml.etree.ElementTree as ET

# Source and destination paths
SVG_DIR = r"d:\GitHub\Surfing\app\icon"
DEST_DIR = r"d:\GitHub\ClashMetaForAndroid\design\src\main\res\drawable"

# Mapping of SVG filenames to Android drawable names
ICON_MAPPING = {
    "HK.svg": "ic_region_hk",
    "JP.svg": "ic_region_jp",
    "US.svg": "ic_region_us",
    "Singapore.svg": "ic_region_sg",
    "CN.svg": "ic_region_cn",
    "All.svg": "ic_region_all",
    "Globe.svg": "ic_globe",
    "Google.svg": "ic_google",
    "YouTube.svg": "ic_youtube",
    "Telegram.svg": "ic_telegram",
    "Twitter.svg": "ic_twitter",
    "Netflix.svg": "ic_netflix",
    "OpenAI.svg": "ic_openai",
    "Steam.svg": "ic_steam",
    "Discord.svg": "ic_discord",
    "GitHub.svg": "ic_github",
    "Microsoft.svg": "ic_microsoft",
    "Apple.svg": "ic_apple",
    "TikTok.svg": "ic_tiktok",
    "Spotify.svg": "ic_spotify",
    "Facebook.svg": "ic_facebook",
    "Ai.svg": "ic_ai",
    "BiliBili.svg": "ic_bilibili",
    "DouYin.svg": "ic_douyin",
    "XiaoHongShu.svg": "ic_xiaohongshu",
    "DNS.svg": "ic_dns_custom",
    "No-ads-all.svg": "ic_no_ads",
    "WebRTC.svg": "ic_webrtc",
    "Steam.svg": "ic_steam",
    "GoogleFCM.svg": "ic_google_fcm",
    "Meter.svg": "ic_meter",
    "Return.svg": "ic_return",
    "Update.svg": "ic_update",
    "User.svg": "ic_user",
}

def parse_svg_path(svg_content):
    """Extract path data and attributes from SVG content."""
    # Remove XML declaration and DOCTYPE
    svg_content = re.sub(r'<\?xml[^>]*\?>', '', svg_content)
    svg_content = re.sub(r'<!DOCTYPE[^>]*>', '', svg_content)
    
    # Parse SVG
    try:
        root = ET.fromstring(svg_content)
    except ET.ParseError as e:
        print(f"  Error parsing SVG: {e}")
        return None, None, None
    
    # Get viewBox
    viewBox = root.get('viewBox', '0 0 1024 1024')
    parts = viewBox.split()
    if len(parts) == 4:
        width = float(parts[2])
        height = float(parts[3])
    else:
        width = 1024
        height = 1024
    
    # Extract paths
    paths = []
    ns = {'svg': 'http://www.w3.org/2000/svg'}
    
    # Find all path elements
    for path in root.iter('{http://www.w3.org/2000/svg}path'):
        d = path.get('d', '')
        fill = path.get('fill', '#000000')
        if d:
            paths.append((d, fill))
    
    # Also check without namespace
    for path in root.iter('path'):
        d = path.get('d', '')
        fill = path.get('fill', '#000000')
        if d:
            paths.append((d, fill))
    
    return width, height, paths

def create_vector_drawable(name, width, height, paths):
    """Create Android Vector Drawable XML content."""
    # Convert path data format
    # Android Vector Drawable uses the same path data format as SVG
    
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="24dp"',
        f'    android:height="24dp"',
        f'    android:viewportWidth="{width}"',
        f'    android:viewportHeight="{height}">',
    ]
    
    for i, (d, fill) in enumerate(paths):
        # Convert fill color
        fill_color = fill.upper()
        if not fill_color.startswith('#'):
            fill_color = '#000000'
        
        # Ensure 8-digit ARGB format
        if len(fill_color) == 7:  # #RRGGBB
            fill_color = '#FF' + fill_color[1:]
        elif len(fill_color) == 4:  # #RGB
            r, g, b = fill_color[1], fill_color[2], fill_color[3]
            fill_color = f'#FF{r}{r}{g}{g}{b}{b}'
        
        lines.append(f'    <path')
        lines.append(f'        android:fillColor="{fill_color}"')
        lines.append(f'        android:pathData="{d}" />')
    
    lines.append('</vector>')
    return '\n'.join(lines)

def main():
    # Create destination directory if it doesn't exist
    os.makedirs(DEST_DIR, exist_ok=True)
    
    # Process each SVG file
    for svg_name, drawable_name in ICON_MAPPING.items():
        svg_path = os.path.join(SVG_DIR, svg_name)
        dest_path = os.path.join(DEST_DIR, f"{drawable_name}.xml")
        
        if not os.path.exists(svg_path):
            print(f"Skipping {svg_name}: file not found")
            continue
        
        print(f"Converting {svg_name} -> {drawable_name}.xml")
        
        # Read SVG file
        with open(svg_path, 'r', encoding='utf-8') as f:
            svg_content = f.read()
        
        # Parse SVG
        width, height, paths = parse_svg_path(svg_content)
        
        if paths is None or len(paths) == 0:
            print(f"  Warning: No paths found in {svg_name}")
            continue
        
        # Create Vector Drawable
        xml_content = create_vector_drawable(drawable_name, width, height, paths)
        
        # Write to file
        with open(dest_path, 'w', encoding='utf-8') as f:
            f.write(xml_content)
        
        print(f"  Created {drawable_name}.xml with {len(paths)} paths")
    
    print("\nDone! Converted icons are in:", DEST_DIR)

if __name__ == '__main__':
    main()
