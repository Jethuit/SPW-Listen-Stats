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
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', classpath,
    f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}', '-jvm-target', '25',
    '-d', str(scratch), str(ROOT / 'tests/BackButtonVisualCheck.kt')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
output = ROOT / 'build/screenshots/back-button-v0.1.2.png'
native_dir = (args.spw_dir or builder.DEFAULT_SPW_DIR) / 'app'
native_temp = scratch / 'native-temp'
native_temp.mkdir(exist_ok=True)
subprocess.run([java, f'-Dskiko.library.path={native_dir}', f'-Djna.tmpdir={native_temp}',
    f'-Djava.io.tmpdir={native_temp}', '--enable-native-access=ALL-UNNAMED',
    '-cp', str(scratch) + os.pathsep + classpath,
    'BackButtonVisualCheckKt', str(output)], check=True, env=environment)
if args.reference:
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
