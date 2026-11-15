"""Build once and check startup ordering/cancellation in isolated synthetic state. No SPW launch."""
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
previous = b.BUILD / 'libs/SPW-Stats-v0.5.0.spmod'
previous_digest = hashlib.sha256(previous.read_bytes()).hexdigest() if previous.exists() else None
work = b.BUILD / 'tests' / f'plugin-startup-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
(b.BUILD / 'tests/host-discovery').mkdir(parents=True, exist_ok=True)
classes = b.BUILD / 'plugin-classes'
# compile_kotlin removes this output directory. Check its resolved boundary first.
assert classes.resolve().is_relative_to(b.BUILD.resolve()) and classes.resolve() != b.BUILD.resolve()
jdk, compiler = b.find_jdk(), b.find_kotlinc()
kotlin = compiler.parent.parent
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


run(['git', 'diff', '--check'], 'diff-check')
b.compile_kotlin(jdk, compiler, b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
fixture = work / 'fixtures'
fixture.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes,
    b.TOOLS / 'spw-compose-compile-index.jar', b.TOOLS / 'spw-host.jar', b.TOOLS / 'sqlite-jdbc.jar']))
java = str(jdk / 'bin/java.exe')
run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, '-jvm-target', '25', '-d', str(fixture),
    *[str(root / 'tests' / name) for name in ('HostDiscoveryRegressionTest.kt', 'NavigationRegressionTest.kt',
        'NavigationTestSupport.kt', 'SnapshotRegressionTest.kt')]], 'compile-regressions')
for main, args in [('HostDiscoveryRegressionTestKt', [str(b.TOOLS / 'spw-host.jar')]),
                   ('NavigationRegressionTestKt', [])]:
    run([java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Duser.timezone=UTC',
        f'-Dorg.sqlite.tmpdir={work}', '-cp', cp, main, *args], main)
    print((work / f'{main}.log').read_text(encoding='utf-8').strip(), flush=True)
b.package(classes)
run(['py', '-3.12', 'tests/spmod-structure.py', str(package)], 'package-structure')
if previous_digest is not None:
    assert hashlib.sha256(previous.read_bytes()).hexdigest() == previous_digest
print('PASS startup ordering, snapshot/navigation and hook lifecycle checks; previous release unchanged')
print('LIMIT: no real-host startup speed, live hook injection or window interaction measured')
print('SHA256', hashlib.sha256(package.read_bytes()).hexdigest())
