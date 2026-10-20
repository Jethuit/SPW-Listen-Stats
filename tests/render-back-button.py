"""Render the isolated back button and optionally compare a user's host screenshot."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--reference', type=Path)
parser.add_argument('--spw-dir', type=Path)
parser.add_argument('--layout', action='store_true', help='render responsive frame and footer checks')
parser.add_argument('--calendar', action='store_true', help='check fixed month-navigation controls')
parser.add_argument('--colors', action='store_true', help='render sampled card and tooltip colors')
parser.add_argument('--ranking', action='store_true', help='render three ranking modes at narrow and wide widths')
parser.add_argument('--material', action='store_true', help='check retained host composition and material background isolation')
parser.add_argument('--acrylic', action='store_true', help='compare native Acrylic reference colors on sampled flat backdrops')
parser.add_argument('--acrylic-ranking', action='store_true', help='render actual Acrylic rows and pointer hover')
parser.add_argument('--material-ranking', action='store_true', help='check no row dividers and one closing line across all four styles')
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
scratch = ROOT / 'build/tests/back-button'
scratch.mkdir(parents=True, exist_ok=True)
classpath = os.pathsep.join(map(str, [kotlin / 'lib/kotlin-stdlib.jar', ROOT / 'build/plugin-classes',
    *builder.saltui_jars(), ROOT / 'build/tools/spw-host.jar']))
java = str(jdk / 'bin/java.exe')
test_name = ('AcrylicColorsVisualCheck' if args.acrylic else 'MaterialLayerVisualCheck' if args.material else 'RankingModesVisualCheck' if args.ranking or args.acrylic_ranking or args.material_ranking else 'CardColorsVisualCheck' if args.colors else 'CalendarNavigationVisualCheck' if args.calendar else
             'ResponsiveLayoutVisualCheck' if args.layout else 'BackButtonVisualCheck')
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', classpath,
    f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}', '-jvm-target', '25',
    '-d', str(scratch), str(ROOT / 'tests' / f'{test_name}.kt')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
output = ROOT / 'build/screenshots' / f'back-button-v{builder.PLUGIN_VERSION}.png'
if args.layout:
    output = ROOT / 'build/screenshots' / f'responsive-v{builder.PLUGIN_VERSION}'
if args.calendar:
    output = ROOT / 'build/screenshots' / f'calendar-v{builder.PLUGIN_VERSION}'
if args.colors:
    output = ROOT / 'build/screenshots' / f'card-colors-v{builder.PLUGIN_VERSION}'
if args.ranking:
    output = ROOT / 'build/screenshots' / f'ranking-v{builder.PLUGIN_VERSION}'
if args.material:
    output = ROOT / 'build/screenshots' / f'material-v{builder.PLUGIN_VERSION}'
if args.acrylic:
    output = ROOT / 'build/screenshots' / f'acrylic-v{builder.PLUGIN_VERSION}'
if args.acrylic_ranking:
    output = ROOT / 'build/screenshots' / f'acrylic-ranking-v{builder.PLUGIN_VERSION}'
if args.material_ranking:
    output = ROOT / 'build/screenshots' / f'material-ranking-v{builder.PLUGIN_VERSION}'
native_dir = (args.spw_dir or builder.DEFAULT_SPW_DIR) / 'app'
native_temp = scratch / 'native-temp'
native_temp.mkdir(exist_ok=True)
subprocess.run([java, f'-Dskiko.library.path={native_dir}', f'-Djna.tmpdir={native_temp}',
    f'-Djava.io.tmpdir={native_temp}', '--enable-native-access=ALL-UNNAMED',
    '-cp', str(scratch) + os.pathsep + classpath,
    test_name + 'Kt', str(output), *(['materials'] if args.material_ranking else ['acrylic'] if args.acrylic_ranking else [])], check=True, env=environment)
if args.material_ranking:
    files = sorted(output.glob('*.png'))
    assert len(files) == 24, len(files)
    for file in files:
        rendered = Image.open(file).convert('RGB')
        pixels = rendered.load()
        cover_rows = [y for y in range(rendered.height)
                      if any(pixels[x, y] == (30, 90, 180) for x in range(50, 125))]
        assert cover_rows, file.name
        dark, classic = file.name.startswith('dark-'), '-classic-' in file.name
        background = (35, 35, 35) if dark else (248, 248, 248) if classic else (221, 221, 221)
        light_mica = file.name.startswith('light-mica-') and not file.name.startswith('light-mica-alt-')
        line = ((55, 57, 62) if dark else (177, 177, 177) if light_mica else
                (195, 195, 195) if '-acrylic-' in file.name else (233, 234, 237))
        for y in range(min(cover_rows), max(cover_rows)+8):
            assert pixels[10, y] == background, (file.name, y, pixels[10, y])
        closing = [y for y in range(max(cover_rows)+8, max(cover_rows)+35)
                   if pixels[10, y] == line and pixels[rendered.width-10, y] == line]
        assert 1 <= len(closing) <= 2, (file.name, closing)
    print('PASS all 24 layouts: no internal row dividers and exactly one full-width closing line before footer')
elif args.acrylic_ranking:
    for mode in ['song', 'artist', 'album']:
        normal = Image.open(output / f'acrylic-{mode}-760.png').convert('RGB')
        hovered = Image.open(output / f'acrylic-{mode}-760-hover.png').convert('RGB')
        # x=10 avoids covers, rank text and the footer. Scan across row boundaries
        # to detect dividers rather than just checking a palette constant.
        for y in range(130, 350 if mode == 'song' else 270):
            assert normal.getpixel((10, y)) == (221, 221, 221), (mode, y, normal.getpixel((10, y)))
        assert hovered.getpixel((10, 160)) == (194, 194, 194), (mode, hovered.getpixel((10, 160)))
        assert hovered.getpixel((10, 245)) == (221, 221, 221), mode
    print('PASS three actual ranking modes: no row separators; native #C2C2C2 pointer hover and unaffected next row')
elif args.acrylic:
    from collections import Counter
    # Native focused card samples vary with the backdrop. Inactive reference:
    # background #DDDDDD, card #E2E2E2, outline #C7C7C7.
    for name, backdrop, expected in [('focused', (195, 219, 238), (205, 224, 240)),
                                     ('focused-blue', (167, 214, 254), (183, 221, 254)),
                                     ('unfocused', (221, 221, 221), (226, 226, 226))]:
        rendered = Image.open(output / f'acrylic-{name}.png').convert('RGB')
        assert rendered.getpixel((5, 5)) == backdrop
        actual = Counter(rendered.crop((40, 35, 760, 125)).getdata()).most_common(1)[0][0]
        tolerance = 1 if name == 'unfocused' else 2
        assert max(abs(a-b) for a, b in zip(actual, expected)) <= tolerance, (name, actual, expected)
        if name == 'unfocused':
            border = rendered.getpixel((400, 25))
            assert max(abs(channel-199) for channel in border) <= 1, border
            heatmap = Counter(rendered.crop((70, 295, 770, 400)).getdata())
            assert heatmap[(209, 209, 209)] > 500, heatmap.most_common(8)
            assert heatmap[(240, 241, 243)] == 0, 'Opaque classic empty cells leaked into Acrylic'
        print(f'PASS {name}: rendered card {actual} matches native sample {expected} within {tolerance} levels')
    print('PASS inactive outline and translucent heatmap cells; synthetic compositing only, DWM requires user review')
elif args.material:
    for name, expected in [('before.png', (187, 34, 68)), ('hidden.png', (39, 107, 86)),
                           ('restored.png', (187, 34, 68))]:
        actual = Image.open(output / name).convert('RGB').getpixel((200, 150))
        assert actual == expected, (name, actual, expected)
    print('PASS transparent page reveals the synthetic backdrop, never the hidden host page')
elif args.ranking:
    files = sorted(output.glob('*-*-*.png'))
    assert len(files) == 12, len(files)
    for file in files:
        rendered = Image.open(file).convert('RGB')
        # All synthetic embedded covers share this distinctive blue; no actual library is read.
        remaining = {(x, y) for y in range(rendered.height) for x in range(rendered.width)
                     if rendered.getpixel((x, y)) == (30, 90, 180)}
        bounds = []
        while remaining:
            seed = remaining.pop()
            component, pending = [seed], [seed]
            while pending:
                x, y = pending.pop()
                for point in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
                    if point in remaining:
                        remaining.remove(point)
                        pending.append(point)
                        component.append(point)
            if len(component) > 500:
                bounds.append((min(x for x,y in component), min(y for x,y in component),
                               max(x for x,y in component)+1, max(y for x,y in component)+1))
        bounds.sort(key=lambda rect: rect[1])
        assert len(bounds) == (3 if '-song-' in file.name else 2), (file.name, bounds)
        # The 1dp border covers up to two rasterized pixels on each side at 125%.
        outline = (55, 57, 62) if file.name.startswith('dark-') else (233, 234, 237)
        for l, t, r, b in bounds:
            assert 56 <= r-l <= 60 and 56 <= b-t <= 60, (file.name, bounds)
            palette = ((30, 90, 180), outline)
            xs = [x for x in range(l-3, r+3) if rendered.getpixel((x, (t+b)//2)) in palette]
            ys = [y for y in range(t-3, b+3) if rendered.getpixel(((l+r)//2, y)) in palette]
            assert max(xs)-min(xs)+1 == 60 and max(ys)-min(ys)+1 == 60, (file.name, bounds)
        assert all(82 <= following[1]-previous[1] <= 83
                   for previous, following in zip(bounds, bounds[1:])), (file.name, bounds)
    print('PASS 12 layouts: 48dp (60px at 125%) covers and approximately 66dp row pitch')
elif args.colors:
    from collections import Counter
    for theme, expected in [('light', (252, 252, 252)), ('dark', (42, 42, 42))]:
        rendered = Image.open(output / f'cards-{theme}.png').convert('RGB')
        card_colors = Counter(rendered.crop((25, 25, 625, 140)).getdata())
        tooltip_colors = Counter(rendered.crop((0, 175, 650, 400)).getdata())
        assert card_colors[expected] > 40_000, (theme, card_colors.most_common(3))
        assert tooltip_colors[expected] > 8_000, (theme, tooltip_colors.most_common(3))
        print(f'PASS {theme}: card and popup render the sampled #{expected[0]:02X}{expected[1]:02X}{expected[2]:02X} fill')
elif args.calendar:
    widths = []
    for file in sorted(output.glob('month-*.png')):
        im = Image.open(file).convert('RGB')
        remaining = {(x, y) for y in range(im.height) for x in range(im.width)
                     if im.getpixel((x, y)) == (237, 238, 241)}
        bounds = []
        while remaining:
            seed = remaining.pop()
            component, pending = [seed], [seed]
            while pending:
                x, y = pending.pop()
                for point in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
                    if point in remaining:
                        remaining.remove(point)
                        pending.append(point)
                        component.append(point)
            if len(component) > 200:
                bounds.append((min(x for x,y in component), min(y for x,y in component),
                               max(x for x,y in component)+1, max(y for x,y in component)+1))
        bounds.sort()
        assert len(bounds) == 4, (file, bounds)
        measured = [r-l for l,t,r,b in bounds]
        assert measured == [43, 115, 95, 43], (file, measured)
        assert all(b-t == 43 for l,t,r,b in bounds), (file, bounds)
        widths.append(measured)
    assert len(widths) == 4
    print('PASS four month/year labels: fixed 34/92/76/34dp control widths, both arrows 34x34dp at 125%')
elif args.layout:
    before = Image.open(output / 'frame-top.png').convert('RGB').crop((0, 0, 1875, 70))
    after = Image.open(output / 'frame-scrolled.png').convert('RGB').crop((0, 0, 1875, 70))
    assert ImageChops.difference(before, after).getbbox() is None, 'Scrolling content covers the navigation row'
    print('PASS navigation row stays unchanged after scrolling')
elif args.reference:
    # Supplied host screenshot: 125% scaling; the sidebar divider occupies x=275.
    reference = Image.open(args.reference).convert('RGB').crop((276, 50, 340, 114))
    rendered = Image.open(output).convert('RGB').crop((0, 0, 64, 64))
    def ink_bounds(im):
        return im.convert('L').point(lambda value: 255 if value < 200 else 0).getbbox()
    expected, actual = ink_bounds(reference), ink_bounds(rendered)
    difference = sum(ImageStat.Stat(ImageChops.difference(reference, rendered)).mean) / 3
    print(f'Host arrow bounds: {expected}; rendered bounds: {actual}; mean channel error: {difference:.4f}')
    reference.save(scratch / 'reference-crop.png')
    rendered.save(scratch / 'rendered-crop.png')
    assert actual == expected, 'Native button position/size does not match the supplied screenshot'
    assert difference < 1, 'Rendered arrow differs materially from the host reference'
