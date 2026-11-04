#!/usr/bin/env python3
"""One build and targeted synthetic playback/gesture regressions; never invoke the real player."""
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
work = root / 'build/tests' / f'song-playback-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
package = b.BUILD / 'libs' / b.PACKAGE_FILENAME
assert not package.exists(), f'Refusing to overwrite {package}'
preserved = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in (b.BUILD / 'libs').glob('*.spmod')}
jdk, compiler = b.find_jdk(), b.find_kotlinc()
kotlin = compiler.parent.parent
classes = b.BUILD / 'plugin-classes'
assert classes.resolve().is_relative_to(b.BUILD.resolve()) and classes.name == 'plugin-classes'
b.compile_kotlin(jdk, compiler, b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
fixture = work / 'fixtures'
fixture.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes,
    *b.saltui_jars(), b.TOOLS / 'spw-compose-compile-index.jar', b.TOOLS / 'spw-host.jar']))

def run(command, name, env=None):
    log = work / f'{name}.log'
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=root, env=env, stdout=stream, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    if result.returncode:
        print(log.read_text(encoding='utf-8', errors='replace')[-7500:])
        raise SystemExit(result.returncode)

fake_host = work / 'fake-host'
fake_host.mkdir(exist_ok=True)
run([str(jdk / 'bin/java.exe'), '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, '-jvm-target', '25', '-d', str(fake_host),
    *map(str, sorted((root / 'tests/fixtures/song-playback').glob('*.kt')))], 'compile-fake-host')
# fake_host is deliberately absent from cp: production signature checks still inspect the real host.
run([str(jdk / 'bin/java.exe'), '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, f'-Xfriend-paths={classes}',
    f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}', '-jvm-target', '25', '-d', str(fixture),
    str(root / 'tests/SongPlaybackRegressionTest.kt'), str(root / 'tests/RankingRegressionTest.kt')], 'compile-regressions')
env = os.environ.copy()
env['APPDATA'] = str(work / 'appdata')
env['LOCALAPPDATA'] = str(work / 'localappdata')
native = work / 'native'
native.mkdir(exist_ok=True)
java = [str(jdk / 'bin/java.exe'), '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '--enable-native-access=ALL-UNNAMED', f'-Dskiko.library.path={b.DEFAULT_SPW_DIR / "app"}',
    f'-Djna.tmpdir={native}', '-cp', cp]
for entry in ['SongPlaybackRegressionTestKt', 'RankingRegressionTestKt']:
    run([*java, entry, str(work), str(fake_host)], entry, env)
    print((work / f'{entry}.log').read_text(encoding='utf-8').strip())
package = b.package(classes)
import zipfile
with zipfile.ZipFile(package) as archive:
    manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
    assert f'Plugin-Version: {b.PLUGIN_VERSION}' in manifest
    assert all(not name.endswith('RegressionTestKt.class') for name in archive.namelist())
for path, digest in preserved.items():
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, f'Existing package changed: {path}'
print('independent development package', package)
print('sha256', hashlib.sha256(package.read_bytes()).hexdigest())
