"""由用户SVG生成彩色及可选色纯色桌面图标，复用透明轮廓与XML着色。"""
from pathlib import Path
import argparse
import copy
import io
import re
import xml.etree.ElementTree as ET
import numpy as np
import cairosvg
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/images/icon.svg'
SVG_NS = 'http://www.w3.org/2000/svg'
BACKGROUND = {0, 3, 5}
MONO = {1, 4, 6, 9, 10, 14, 16, 21}
TONES = {'amber': '#D38569', 'camellia': '#E24E37', 'sage': '#9AAF87', 'ocean': '#365F82', 'forest': '#436A4D', 'violet': '#6A5F94', 'rose': '#985569', 'slate': '#546574', 'cream': '#FAF0E2', 'ink': '#302A27'}
BACKGROUNDS = {'artwork': '#9AAF87', 'sage': '#9AAF87', 'cream': '#FAF0E2', 'rose': '#F6DEDA', 'ink': '#302A27'}
# 使用项目Material 1.12.0的MaterialColors.getColorRoles(seed, true/false).accent离线生成。
# 每对依次为浅背景深色角色、暖墨背景亮色角色；设备测试逐项与官方API核对。
TONE_ROLES = {
    'amber': ('#8E4D34', '#FFB59B'), 'camellia': ('#B22B19', '#FFB4A6'),
    'sage': ('#516442', '#B8CDA3'), 'ocean': ('#396285', '#A2CBF3'),
    'forest': ('#40674A', '#A6D1AE'), 'violet': ('#62578B', '#CCBEFA'),
    'rose': ('#8B4A5E', '#FFB1C6'), 'slate': ('#4F606F', '#B7C9DA'),
    'cream': ('#645E53', '#CEC5B8'), 'ink': ('#655D59', '#CFC4C0'),
}


def read_artwork(source):
    """严格接受包内31条纯填色路径，避免丢弃不支持的图形能力。"""
    root = ET.parse(source).getroot()
    paths = list(root)
    if root.get('viewBox') != '0 0 1254 1254' or len(paths) != 31:
        raise ValueError('需要1254×1254、31路径SVG源文件')
    for path in paths:
        if path.tag != f'{{{SVG_NS}}}path' or set(path.attrib) != {'d', 'fill', 'transform'}:
            raise ValueError('源图包含未支持的元素或属性')
        if not re.fullmatch(r'#[0-9A-Fa-f]{6}', path.get('fill', '')) or not re.fullmatch(r'translate\(([-\d.]+),([-\d.]+)\)', path.get('transform', '')):
            raise ValueError('源图必须使用纯色和平移变换')
    return root, paths


def raster(paths, indices, mono=None, size=756):
    """从原始路径生成透明图层，头上的山茶花从不由花瓣选项移除。"""
    root = ET.Element('svg', {'xmlns': SVG_NS, 'viewBox': '0 0 1254 1254'})
    if 0 in indices and mono is None:
        # 背景保留原参考图的柔和变色；人物和装饰仍完全使用原SVG轮廓。
        defs = ET.SubElement(root, 'defs')
        gradient = ET.SubElement(defs, 'linearGradient', {'id':'paper','x1':'0','y1':'0','x2':'1','y2':'1'})
        ET.SubElement(gradient,'stop',{'offset':'0','stop-color':'#B7C6A5'})
        ET.SubElement(gradient,'stop',{'offset':'1','stop-color':'#95AA81'})
    for index, path in enumerate(paths):
        if index in indices:
            element = copy.deepcopy(path)
            if mono:
                element.set('fill', mono)
            elif index == 0:
                element.set('fill', 'url(#paper)')
            root.append(element)
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=ET.tostring(root), output_width=size, output_height=size))).convert('RGBA')


def edge_extend(image, size=432):
    """将完整画幅放入64dp区域，边缘延展至108dp，圆形裁切仍保留飘落花瓣。"""
    inner = round(size * 64 / 108)
    content = np.asarray(image.resize((inner, inner), Image.Resampling.LANCZOS).convert('RGB'))
    before = (size - inner) // 2
    return Image.fromarray(np.pad(content, ((before, size-inner-before), (before, size-inner-before), (0, 0)), mode='edge'))


def generate(source, preview_dir):
    """生成10组彩色和100组纯色入口；纯色仅复用两张透明轮廓，不按组合生成位图。"""
    _, paths = read_artwork(source)
    if source.resolve() != SOURCE.resolve():
        SOURCE.write_bytes(source.read_bytes())
    res = ROOT / 'app/src/main/res'
    bitmaps = res / 'drawable-nodpi'
    bitmaps.mkdir(exist_ok=True)
    # 启动页使用透明人物及飘落装饰，桌面背景层不进入此资源。
    splash = Image.new('RGBA', (432, 432))
    splash.alpha_composite(raster(paths, set(range(31)) - BACKGROUND, size=256), (88, 88))
    splash.save(bitmaps / 'ic_splash_portrait_pixels.webp', lossless=True, method=6)
    (res / 'drawable/ic_splash_portrait.xml').write_text('<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/ic_splash_portrait_pixels" android:gravity="fill" android:filter="true" />\n')
    mipmaps = res / 'mipmap-anydpi-v26'
    (res / 'drawable/ic_launcher_empty.xml').write_text('<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#00000000" /></shape>\n')
    for petals in [True, False]:
        suffix = "petals" if petals else "plain"
        mono = Image.new('RGBA', (432, 432))
        mono.alpha_composite(raster(paths, MONO | ({17, 18} if petals else set()), '#FFFFFF', 256), (88, 88))
        mono.save(bitmaps / f'ic_mono_{suffix}_pixels.webp', lossless=True, method=6)
        (res / f'drawable/ic_launcher_mono_{suffix}.xml').write_text(f'<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/ic_mono_{suffix}_pixels" android:gravity="fill" android:filter="true" />\n')
        for tone, roles in TONE_ROLES.items():
            for role, color in zip(['deep', 'light'], roles):
                (res / f'drawable/ic_mono_tint_{tone}_{role}_{suffix}.xml').write_text(f'<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/ic_mono_{suffix}_pixels" android:gravity="fill" android:filter="true" android:tint="{color}" android:tintMode="src_in" />\n')
    rows = []
    aliases = []
    previews = []
    for background, color in BACKGROUNDS.items():
        background_name = f'ic_mono_background_{background}'
        if background == 'artwork':
            edge_extend(raster(paths, BACKGROUND)).save(bitmaps / f'{background_name}_pixels.webp', lossless=True, method=6)
            background_xml = f'<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/{background_name}_pixels" android:gravity="fill" android:filter="true" />\n'
        else:
            background_xml = f'<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="{color}" /></shape>\n'
        (res / f'drawable/{background_name}.xml').write_text(background_xml)
        for petals in [True, False]:
            suffix = 'petals' if petals else 'plain'
            indices = set(range(31)) - BACKGROUND - (set() if petals else {17, 18})
            full = raster(paths, set(range(31)) - (set() if petals else {17, 18})) if background == 'artwork' else Image.new('RGBA', (756, 756), color)
            if background != 'artwork':
                full.alpha_composite(raster(paths, indices))
            icon = edge_extend(full)
            key = f'vector_{background}_{suffix}'
            icon.save(bitmaps / f'ic_{key}_pixels.webp', lossless=True, method=6)
            (res / f'drawable/ic_{key}.xml').write_text(f'<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/ic_{key}_pixels" android:gravity="fill" android:filter="true" />\n')
            choices = [(key, f'ic_{key}', 'ic_launcher_empty')]
            role = 'light' if background == 'ink' else 'deep'
            choices += [(f'mono_{background}_{suffix}_{tone}', background_name, f'ic_mono_tint_{tone}_{role}_{suffix}') for tone in TONES]
            for resource_key, bg, fg in choices:
                xml = f'<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/{bg}" />\n    <foreground android:drawable="@drawable/{fg}" />\n    <monochrome android:drawable="@drawable/ic_launcher_mono_{suffix}" />\n</adaptive-icon>\n'
                (mipmaps / f'ic_{resource_key}.xml').write_text(xml)
                rows.append(f'        "{resource_key}" to R.mipmap.ic_{resource_key},')
                enabled = 'true' if resource_key == 'vector_artwork_petals' else 'false'
                aliases.append(f'''        <activity-alias android:name="${{applicationId}}.launcher.v3.{resource_key}"
            android:targetActivity=".MainActivity" android:exported="true"
            android:enabled="{enabled}" android:icon="@mipmap/ic_{resource_key}" android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>''')
            if petals:
                previews.append(icon)
    # 保留旧资源文件，避免删除共享仓库已有内容；仅新资源显式映射到桌面入口。
    (res / 'drawable/ic_launcher_monochrome.xml').write_text((res / 'drawable/ic_launcher_mono_petals.xml').read_text())
    (res / 'drawable/ic_launcher_foreground.xml').write_text((res / 'drawable/ic_vector_artwork_petals.xml').read_text())
    default_xml = (mipmaps / 'ic_vector_artwork_petals.xml').read_text()
    for name in ['ic_launcher.xml', 'ic_launcher_round.xml']:
        (mipmaps / name).write_text(default_xml)
    mapping = ROOT / 'app/src/main/java/io/github/korenkrita/simulsub/icons/LauncherIconResources.kt'
    mapping.write_text('package io.github.korenkrita.simulsub.icons\n\nimport io.github.korenkrita.simulsub.R\n\n/** 由tools/generate-icon.py生成；显式引用让发行构建保留全部110个真实图标。 */\ninternal object LauncherIconResources {\n    val ids = mapOf(\n' + '\n'.join(rows) + '\n    )\n}\n')
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    content = manifest.read_text()
    begin = content.index('        <!-- v', content.index('</activity>'))
    end = content.rindex('        </activity-alias>') + len('        </activity-alias>')
    manifest.write_text(content[:begin] + '        <!-- v3入口在覆盖安装后提供可用默认入口，开屏再恢复纯色偏好。 -->\n' + '\n'.join(aliases) + content[end:])
    if preview_dir:
        preview_dir.mkdir(parents=True, exist_ok=True)
        images = previews + [Image.new('RGBA', (432, 432), '#9AAF87') for _ in TONES] + [Image.new('RGBA', (432, 432), '#302A27') for _ in TONES]
        sheet = Image.new('RGB', (5 * 168, 5 * 168), '#FAF0E2')
        colors = [roles[0] for roles in TONE_ROLES.values()] + [roles[1] for roles in TONE_ROLES.values()]
        for image, color in zip(images[5:], colors):
            mask = Image.open(bitmaps / 'ic_mono_petals_pixels.webp').convert('RGBA')
            tint = Image.new('RGBA', mask.size, color)
            tint.putalpha(mask.getchannel('A'))
            image.alpha_composite(tint)
        for i, image in enumerate(images):
            visible = image.crop((72, 72, 360, 360)).resize((144, 144), Image.Resampling.LANCZOS).convert('RGBA')
            mask = Image.new('L', (144, 144))
            ImageDraw.Draw(mask).ellipse((0, 0, 143, 143), fill=255)
            visible.putalpha(mask)
            sheet.paste(visible, ((i % 5) * 168 + 12, (i // 5) * 168 + 12), visible)
        sheet.save(preview_dir / 'icon-options.png')
    print('已生成110组真实桌面图标：10组彩色、100组纯色，共用两张纯色轮廓。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='植入用户图标包并生成可选版本')
    parser.add_argument('--source-svg',type=Path,default=SOURCE)
    parser.add_argument('--preview-dir',type=Path)
    args=parser.parse_args()
    generate(args.source_svg,args.preview_dir)
