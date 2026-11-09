"""Build once and exercise stop/import ordering against isolated SPW classes. No installation."""
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
package = b.BUILD / 'libs' / b.PACKAGE_FILENAME
assert not package.exists(), f'Refusing to overwrite {package}'
previous = b.BUILD / 'libs/SPW-Stats-v0.4.2-dev.11.spmod'
digest = hashlib.sha256(previous.read_bytes()).hexdigest()
work = b.BUILD / 'tests' / f'plugin-stop-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
(b.BUILD / 'tests/host-discovery').mkdir(parents=True, exist_ok=True)
classes = b.BUILD / 'plugin-classes'
assert classes.resolve().is_relative_to(b.BUILD.resolve())
jdk, compiler = b.find_jdk(), b.find_kotlinc()
kotlin = compiler.parent.parent
if '--reuse-classes' not in sys.argv:
    b.compile_kotlin(jdk, compiler, b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
else:
    assert classes.is_dir(), 'Reuse is only for fixture-only corrections after compiling current source'
fixture = work / 'fixtures'
fixture.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes,
    b.TOOLS / 'spw-compose-compile-index.jar', b.TOOLS / 'spw-host.jar', b.TOOLS / 'sqlite-jdbc.jar']))
environment = os.environ.copy()
environment['APPDATA'] = str(work / 'appdata')
environment['LOCALAPPDATA'] = str(work / 'localappdata')

def run(command, name):
    log = work / f'{name}.log'
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    if result.returncode:
        print(log.read_text(encoding='utf-8', errors='replace')[-7500:])
        raise SystemExit(result.returncode)

java = str(jdk / 'bin/java.exe')
run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, '-jvm-target', '25', '-d', str(fixture),
    *[str(root / 'tests' / name) for name in ('HostDiscoveryRegressionTest.kt', 'NavigationRegressionTest.kt',
        'NavigationTestSupport.kt', 'SnapshotRegressionTest.kt')]], 'compile-regressions')
for main, args in [('HostDiscoveryRegressionTestKt', [str(b.TOOLS / 'spw-host.jar')]),
                   ('NavigationRegressionTestKt', [])]:
    run([java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Duser.timezone=UTC',
        f'-Dorg.sqlite.tmpdir={work}', '-cp', cp, main, *args], main)
    print((work / f'{main}.log').read_text(encoding='utf-8').strip())
b.package(classes)
run(['py', '-3.12', 'tests/spmod-structure.py', str(package)], 'package-structure')
assert hashlib.sha256(previous.read_bytes()).hexdigest() == digest
print('PASS isolated import/stop checks; previous package unchanged')
print('SHA256', hashlib.sha256(package.read_bytes()).hexdigest())
