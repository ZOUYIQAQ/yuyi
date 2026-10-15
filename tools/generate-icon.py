"""从无描边色块生成火羽同传矢量图标；可选渲染预览需要 cairosvg 和 Pillow。"""
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[1]
BACKGROUND = '#F6EBDD'
# 所有细节均为封闭填色路径，不使用描边、渐变或投影。
SHAPES = [
    # 后发是一整块较深铜色，轮廓收窄，发梢保持利落。
    ('#BB7B60', 'M30,65 L30,44 C30,30 40,22 52,22 C62,21 70,27 74,35 C78,39 79,44 79,49 L78,67 L72,75 L64,72 L40,75 L32,69 Z'),
    # 肩领和颈部先绘制，蝴蝶结靠近下巴，避免细长脖颈。
    ('#FFFDF8', 'M45,72 L55,76 L65,72 C74,77 79,84 82,94 L27,94 C31,83 36,76 45,72 Z'),
    ('#FFF0DA', 'M49,68 L60,68 L60,75 L55,79 L49,75 Z'),
    # 下颌向中央明确收拢，脸部全部留白；不画眼眉、鼻口或腮红。
    ('#FFF0DA', 'M41,40 Q44,43 48,43 L49,36 Q51,42 54,44 L61,44 L62,36 Q65,40 68,42 L68,52 C68,59 63,65 55,71 C47,66 42,59 41,52 Z'),
    # 前发为第二个连续大色块，不使用发丝、细线和小块高光。
    ('#D5A080', 'M30,44 C30,30 40,22 52,22 C62,21 70,27 74,35 L74,58 L68,66 L68,42 Q65,40 62,36 L61,44 L54,44 Q51,42 49,36 L48,43 Q44,43 41,40 L41,52 L36,68 L32,72 L30,65 Z'),
    # 飘带、山茶花与蝴蝶结共用红色，花瓣只保留宽阔的叠合关系。
    ('#C63D40', 'M31,35 L34,35 L32,59 L29,72 L26,77 L29,52 Z M36,35 L39,35 L37,63 L34,70 L35,52 Z'),
    ('#C63D40', 'M33,37 C28,35 28,31 32,28 C31,24 35,23 39,25 C43,22 47,24 48,28 C53,30 51,34 48,36 C48,41 44,42 40,40 C36,43 32,41 33,37 Z'),
    ('#EF7752', 'M32,31 C32,27 35,26 39,28 C43,25 47,27 47,31 C49,34 45,37 41,36 C37,38 33,36 32,31 Z'),
    ('#C63D40', 'M37,32 C37,29 41,28 43,31 C46,32 43,35 40,35 L37,34 Z'),
    ('#C63D40', 'M53,77 L44,74 L44,85 L53,81 L55,85 L58,81 L66,85 L66,74 L57,77 Z'),
    ('#E8BC69', 'M53,76 L57,76 L58,80 L55,83 L52,80 Z'),
]

MONO = [
    'M30,65 L30,44 C30,30 40,22 52,22 C62,21 70,27 74,35 C78,39 79,44 79,49 L78,67 L68,78 L74,94 L35,94 L40,77 L32,69 Z M41,40 Q44,43 48,43 L49,36 Q51,42 54,44 L61,44 L62,36 Q65,40 68,42 L68,52 C68,59 63,65 55,71 C47,66 42,59 41,52 Z',
    'M33,37 C28,35 28,31 32,28 C31,24 35,23 39,25 C43,22 47,24 48,28 C53,30 51,34 48,36 C48,41 44,42 40,40 C36,43 32,41 33,37 Z M31,35 L34,35 L32,59 L29,72 L26,77 L29,52 Z',
]


def svg_text(shapes, background=True):
    """输出与自适应图标可见区域一致的可编辑 SVG。"""
    paths = [f'<path fill="{color}" d="{data}"/>' for color, data in shapes]
    backdrop = f'<path fill="{BACKGROUND}" d="M0,0H108V108H0Z"/>' if background else ''
    return '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">\n'+backdrop+'\n'+'\n'.join(paths)+'\n</svg>\n'


def vector_text(shapes, even_odd=False):
    """输出 Android 原生矢量资源，单色图用镂空保留人脸负形。"""
    fill_type = ' android:fillType="evenOdd"' if even_odd else ''
    paths = [f'    <path android:fillColor="{color}"{fill_type} android:pathData="{data}" />' for color, data in shapes]
    return '<?xml version="1.0" encoding="utf-8"?>\n<!-- 火羽ひのめ：橙棕短发、留白面部、红色山茶花；仅使用无描边大色块。 -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'+'\n'.join(paths)+'\n</vector>\n'


def generate(preview_dir=None):
    """同步生成桌面前景、背景、单色资源和文档图标，并可选导出验收图。"""
    drawable = ROOT/'app/src/main/res/drawable'
    (drawable/'ic_launcher_foreground.xml').write_text(vector_text(SHAPES))
    (drawable/'ic_launcher_background.xml').write_text(vector_text([(BACKGROUND, 'M0,0H108V108H0Z')]))
    mono = [('#FFFFFF', path) for path in MONO]
    (drawable/'ic_launcher_monochrome.xml').write_text(vector_text(mono, True))
    source = svg_text(SHAPES)
    (ROOT/'docs/images/icon.svg').write_text(source)
    if preview_dir:
        import cairosvg
        from PIL import Image, ImageDraw
        import io
        preview_dir.mkdir(parents=True, exist_ok=True)
        artwork = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=source.encode(), output_width=512, output_height=512))).convert('RGBA')
        artwork.save(preview_dir/'icon-square.png')
        artwork.save(ROOT/'docs/images/icon.png')
        for name, radius in [('round', 256), ('squircle', 112)]:
            mask = Image.new('L', (512,512)); draw = ImageDraw.Draw(mask)
            draw.rounded_rectangle((0,0,511,511), radius=radius, fill=255)
            result=artwork.copy();result.putalpha(mask);result.save(preview_dir/f'icon-{name}.png')
        sheet=Image.new('RGB',(1200,630),'#F4F1ED')
        for index,name in enumerate(['square','round','squircle']):
            icon=Image.open(preview_dir/f'icon-{name}.png');sheet.paste(icon.resize((336,336)),(32+index*400,40),icon.resize((336,336)))
            for j,size in enumerate([96,48,32]):
                small=icon.resize((size,size),Image.Resampling.LANCZOS)
                sheet.paste(small,(64+index*400+j*112,455),small)
        sheet.save(preview_dir/'icon-review.png')
    print('已生成火羽同传无描边几何图标。')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description='生成火羽同传图标')
    parser.add_argument('--preview-dir', type=Path, help='可选：输出不同遮罩和小尺寸预览')
    generate(parser.parse_args().preview_dir)
