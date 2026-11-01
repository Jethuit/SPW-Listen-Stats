"""One chart-only validation round: dev.11 reference, cache invariants, pixels and clean timings.

No player is launched and no listening records are loaded. Probes are applied to
class copies only; the new installation package is produced after checks pass.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import math
import os
import statistics
import subprocess
import sys
import zipfile
from PIL import Image, ImageChops

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
work = ROOT / 'build/tests' / f'trend-curve-cache-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
classes = (b.BUILD / 'plugin-classes').resolve()
assert classes.is_relative_to(b.BUILD.resolve()) and classes.name == 'plugin-classes'
package = b.BUILD / 'libs' / b.PACKAGE_FILENAME
assert not package.exists(), f'Refusing to overwrite {package}'
reference = b.BUILD / 'libs/SPW-Stats-v0.3.5-dev.11.spmod'
assert reference.is_file(), 'The immutable dev.11 baseline is required'
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
preserved = {p: digest(p) for p in (b.BUILD / 'libs').glob('*.spmod')}
assert digest(reference) == 'a0079bde2bd5928e5824840dd91463845ecbded43d16f09436f177cfda5ecdb9', 'The dev.11 reference was replaced'
baseline = work / 'baseline'
baseline.mkdir(exist_ok=True)
with zipfile.ZipFile(reference) as archive:
    for name in archive.namelist():
        if name.startswith('classes/') and not name.endswith('/'):
            target = baseline / name.removeprefix('classes/')
            assert target.resolve().is_relative_to(baseline.resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
source = ROOT / 'plugin/src/main/kotlin/com/jethuit/spw/listenstats/ui/TrendChart.kt'
# Canonical LF source fingerprint of the unchanged dev.11 line/monotone functions.
# This avoids requiring the original local commit object in a fresh GitHub checkout.
algorithm = source.read_text(encoding='utf-8').split('private fun linePath(', 1)[1]
assert hashlib.sha256(algorithm.encode('utf-8')).hexdigest() == 'b64be3e7e5edc2e19c6e6acb3153b605234f9dbb55f5c7306b52523b585e5690', 'Interpolation changed'
jdk, kotlin = b.find_jdk(), b.find_kotlinc().parent.parent
java = str(jdk / 'bin/java.exe')
fixture = work / 'fixture-classes'
fixture.mkdir(exist_ok=True)
native = work / 'native-temp'
native.mkdir(exist_ok=True)
def run(command, label, env=None):
    with (work / f'{label}.log').open('w', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    print(label, result.returncode, flush=True)
    if result.returncode:
        print((work / f'{label}.log').read_text(encoding='utf-8', errors='replace')[-9000:])
        raise SystemExit(result.returncode)

# Keep all production classes separate from the fixtures and instrumented overlays.
b.compile_kotlin(jdk, b.find_kotlinc(), b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes,
    *b.saltui_jars(), b.BUILD / 'tools/spw-compose-compile-index.jar', b.BUILD / 'tools/spw-host.jar']))
run([str(jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', str(b.BUILD / 'tools/spw-host.jar'),
    '-d', str(fixture), str(ROOT / 'tests/TrendChartProbe.java')], 'probe-compile')
run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib',
    '-classpath', cp, f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}', '-jvm-target', '25',
    '-d', str(fixture), str(ROOT / 'tests/TrendChartPerformanceCheck.kt'),
    str(ROOT / 'tests/ListeningDetailsVisualCheck.kt')], 'fixture-compile')
environment = os.environ.copy()
environment['APPDATA'] = str(work / 'appdata')
environment['LOCALAPPDATA'] = str(work / 'localappdata')
flags = [java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '--enable-native-access=ALL-UNNAMED',
    f'-Dskiko.library.path={b.DEFAULT_SPW_DIR / "app"}', f'-Djna.tmpdir={native}']
data = {}
reuse_baseline = '--reuse-baseline' in sys.argv[1:]
for variant, original in [('baseline', baseline), ('candidate', classes)]:
    overlay = work / f'{variant}-overlay'
    relative = Path('com/jethuit/spw/listenstats/ui/TrendChartKt.class')
    run([java, '-cp', cp, 'TrendChartProbe', str(original / relative), str(overlay / relative)], f'{variant}-probe')
    if variant == 'baseline' and reuse_baseline:
        for kind in ['instrumented', 'clean']:
            mode = f'{variant}-{kind}'
            data[mode] = json.loads((work / mode / f'{mode}.json').read_text(encoding='utf-8'))
        # Only corrected candidate assertions changed; replay the visual/date reference freshly.
        runtime = str(overlay) + os.pathsep + str(original) + os.pathsep + cp
        run([*flags, '-cp', runtime, 'TrendChartPerformanceCheckKt', str(work / 'baseline-instrumented'),
             'baseline-validation'], 'baseline-validation', environment)
        continue
    for kind in ['instrumented', 'clean']:
        mode = f'{variant}-{kind}'
        runtime = os.pathsep.join(map(str, ([overlay] if kind == 'instrumented' else []) + [original])) + os.pathsep + cp
        run([*flags, '-cp', runtime, 'TrendChartPerformanceCheckKt', str(work / mode), mode], mode, environment)
        data[mode] = json.loads((work / mode / f'{mode}.json').read_text(encoding='utf-8'))
        if variant == 'candidate' and kind == 'instrumented':
            for row in data[mode]:
                assert row['first']['geometryPoints'] == row['points']
                assert row['first']['calls'][4] == 1
                for key in ['hover', 'sameBucket']:
                    for frame in row[key]:
                        assert frame['calls'][0] == frame['calls'][1] == frame['calls'][4] == 0, (row['months'], key, frame)

base_images = work / 'baseline-instrumented'
new_images = work / 'candidate-instrumented'
pixel_checks = []
for image in sorted(base_images.glob('*.png')):
    with Image.open(image) as a, Image.open(new_images / image.name) as z:
        diff = ImageChops.difference(a.convert('RGBA'), z.convert('RGBA'))
        maximum = max(high for _, high in diff.getextrema())
        # Transparent RGBA8 caching adds a compositing boundary. Inspected diffs show
        # rounding at overlapped grid/fill/stroke edges, not shifted geometry. Bound
        # both amplitude and affected area; record the differences rather than claim exact pixels.
        over_one = sum(max(pixel) > 1 for pixel in diff.getdata())
        fraction = over_one / (diff.width * diff.height)
        assert maximum <= 4 and fraction <= .002, (image.name, maximum, fraction)
        pixel_checks.append({'image': image.name, 'maxChannelDifference': maximum, 'fractionAboveOne': fraction})
base_validation = json.loads((base_images / 'validation.json').read_text(encoding='utf-8'))
new_validation = json.loads((new_images / 'validation.json').read_text(encoding='utf-8'))
assert base_validation['hits'] == new_validation['hits'], 'Date hit mapping changed'
assert len(new_validation['hits']) == 3, 'All three long-range click samples must hit a date'
run([*flags, '-cp', cp, 'ListeningDetailsVisualCheckKt', str(work / 'details')], 'details-regression', environment)

def stats(values):
    values = sorted(values)
    return {'medianMs': statistics.median(values) / 1e6,
            'p95Ms': values[math.ceil(len(values) * .95) - 1] / 1e6}
summary = {'baseline': '0.3.5-dev.11', 'candidate': b.PLUGIN_VERSION,
    'dimensions': [1200, 340], 'density': 1, 'rounds': 5,
    'firstDefinition': 'new scene creation and first render after common warmup; buckets excluded',
    'hoverDefinition': 'one pointer event plus render; no display vsync; synthetic daily data',
    'timings': {}, 'counts': {}, 'pixels': pixel_checks, 'dateHits': new_validation['hits']}
for variant in ['baseline', 'candidate']:
    summary['timings'][variant] = []
    summary['counts'][variant] = []
    for months in [1, 12, 60]:
        rows = [r for r in data[f'{variant}-clean'] if r['months'] == months]
        row = {'months': months, 'points': rows[0]['points'], 'first': stats([r['first']['wallNs'] for r in rows])}
        for key in ['hover', 'sameBucket']:
            row[key] = stats([f['wallNs'] for r in rows for f in r[key]])
        summary['timings'][variant].append(row)
        rows = [r for r in data[f'{variant}-instrumented'] if r['months'] == months]
        summary['counts'][variant].append({'months': months, **{key: {
            'events': sum(len(r[key]) for r in rows),
            'paths': sum(f['calls'][0] for r in rows for f in r[key]),
            'staticDraws': sum(f['calls'][1] for r in rows for f in r[key]),
            'geometryBuilds': sum(f['calls'][4] for r in rows for f in r[key])} for key in ['hover', 'sameBucket']}})

# Preserve the completed Hook/update implementation byte-for-byte outside this chart.
unchanged = 0
for old_file in baseline.rglob('*.class'):
    relative = old_file.relative_to(baseline)
    if relative.name.startswith('TrendChartKt'):
        continue
    assert (classes / relative).read_bytes() == old_file.read_bytes(), f'Unrelated class changed: {relative}'
    unchanged += 1
summary['unchangedOtherClasses'] = unchanged
package = b.package(classes)
run([sys.executable, str(ROOT / 'tests/spmod-structure.py'), str(package)], 'package-structure')
assert all(digest(p) == value for p, value in preserved.items()), 'An existing package changed'
run(['git', 'diff', '--check'], 'diff-check')
(work / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary['timings'], ensure_ascii=False, indent=2))
print(f'PASS chart-only checks, {len(pixel_checks)} image comparisons, dates, cache invalidation and {unchanged} unrelated classes unchanged')
print(package)
