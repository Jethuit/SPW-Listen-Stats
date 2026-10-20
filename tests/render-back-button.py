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
test_name = ('RankingModesVisualCheck' if args.ranking else 'CardColorsVisualCheck' if args.colors else 'CalendarNavigationVisualCheck' if args.calendar else
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
native_dir = (args.spw_dir or builder.DEFAULT_SPW_DIR) / 'app'
native_temp = scratch / 'native-temp'
native_temp.mkdir(exist_ok=True)
subprocess.run([java, f'-Dskiko.library.path={native_dir}', f'-Djna.tmpdir={native_temp}',
    f'-Djava.io.tmpdir={native_temp}', '--enable-native-access=ALL-UNNAMED',
    '-cp', str(scratch) + os.pathsep + classpath,
    test_name + 'Kt', str(output)], check=True, env=environment)
if args.colors:
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
