"""One build/check round for the configurable native statistics entry. No installation."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
work = root / 'build/tests' / f'entry-placement-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
package = b.BUILD / 'libs' / b.PACKAGE_FILENAME
assert not package.exists(), f'Refusing to overwrite {package}'
previous = b.BUILD / 'libs/SPW-Stats-v0.4.2-dev.6.spmod'
digest = hashlib.sha256(previous.read_bytes()).hexdigest()
classes = b.BUILD / 'plugin-classes'
assert classes.resolve().is_relative_to(b.BUILD.resolve())
jdk, compiler = b.find_jdk(), b.find_kotlinc()
kotlin = compiler.parent.parent
if '--reuse-classes' not in sys.argv:
    b.compile_kotlin(jdk, compiler, b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
else:
    assert classes.is_dir(), 'Reuse is only for fixture-only corrections after compiling the current source'
fixture = work / 'fixtures'
fixture.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes, *b.saltui_jars(),
    b.TOOLS / 'spw-compose-compile-index.jar', b.TOOLS / 'spw-host.jar']))

def run(command, name):
    log = work / f'{name}.log'
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    if result.returncode:
        print(log.read_text(encoding='utf-8', errors='replace')[-6500:])
        raise SystemExit(result.returncode)

environment = os.environ.copy()
environment['APPDATA'] = str(work / 'appdata')
environment['LOCALAPPDATA'] = str(work / 'localappdata')
native = work / 'native'
native.mkdir(exist_ok=True)
java = [str(jdk / 'bin/java.exe'), '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '--enable-native-access=ALL-UNNAMED', f'-Dskiko.library.path={b.DEFAULT_SPW_DIR / "app"}',
    f'-Djna.tmpdir={native}', '-cp', cp]
run([str(jdk / 'bin/java.exe'), '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}',
    '-jvm-target', '25', '-d', str(fixture), str(root / 'tests/EntryPlacementRegressionTest.kt'),
    str(root / 'tests/HostDiscoveryRegressionTest.kt')], 'compile-regressions')
suites = [('EntryPlacementRegressionTestKt', []),
          ('HostDiscoveryRegressionTestKt', [str(b.TOOLS / 'spw-host.jar')])]
if '--resume-host' in sys.argv:
    assert '--reuse-classes' in sys.argv, 'Resume only after entry-placement checks have passed'
    suites = suites[1:]
if '--ui-only' in sys.argv:
    assert '--resume-host' not in sys.argv, 'Choose UI-only or host resume'
    suites = suites[:1]
for main, args in suites:
    run([*java, main, *args], main)
    print((work / f'{main}.log').read_text(encoding='utf-8').strip())
if '--ui-only' not in sys.argv:
    run([str(jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', cp, '-d', str(fixture),
        str(root / 'tests/TitlebarHookProbe.java')], 'compile-host-resolver')
    run([*java, 'TitlebarHookProbe', '1.19.3', str(b.TOOLS / 'spw-host.jar')], 'host-resolver')
    run(['py', '-3.12', 'tests/run-data-tests.py', 'navigation'], 'navigation')
preferences = json.loads((b.RESOURCES / 'preference_config.json').read_text(encoding='utf-8'))
switches = [p for c in preferences['configs'] for p in c['preferences'] if p.get('key') == 'sidebarEntry']
assert len(switches) == 1 and switches[0]['type'] == 'switch' and switches[0]['default_value'] is False
b.package(classes)
run(['py', '-3.12', 'tests/spmod-structure.py', str(package)], 'package-structure')
assert hashlib.sha256(previous.read_bytes()).hexdigest() == digest
print('PASS single persisted switch; previous package unchanged')
print('SHA256', hashlib.sha256(package.read_bytes()).hexdigest())
