"""Build once, check cover quality/request lifecycle, and save synthetic visual comparisons."""
import contextlib
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
previous = b.BUILD / 'libs/SPW-Stats-v0.5.1-dev.4.spmod'
previous_digest = hashlib.sha256(previous.read_bytes()).hexdigest() if previous.exists() else None
work = b.BUILD / 'tests' / f'cover-resampling-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
classes = b.BUILD / 'plugin-classes'
assert classes.resolve().is_relative_to(b.BUILD.resolve()) and classes.resolve() != b.BUILD.resolve()
jdk, compiler = b.find_jdk(), b.find_kotlinc()
kotlin = compiler.parent.parent
java = str(jdk / 'bin/java.exe')
environment = os.environ.copy()
environment['APPDATA'] = str(work / 'appdata')
environment['LOCALAPPDATA'] = str(work / 'localappdata')


def run(command, name):
    log = work / f'{name}.log'
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    output = log.read_text(encoding='utf-8', errors='replace')
    print(name, result.returncode, flush=True)
    if result.returncode:
        print(output[-7500:])
        raise SystemExit(result.returncode)
    return output


run(['git', 'diff', '--check'], 'diff-check')
with (work / 'compile.log').open('w', encoding='utf-8') as log:
    with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
        b.compile_kotlin(jdk, compiler, b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
print('PASS Kotlin compilation', flush=True)
fixture = work / 'fixtures'
fixture.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes, b.TOOLS / 'spw-host.jar']))
run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, '-jvm-target', '25', '-d', str(fixture),
    str(root / 'tests/CoverRegressionTest.kt'), str(root / 'tests/CoverResamplingRegressionTest.kt')], 'compile-regressions')
for name in ('CoverRegressionTest', 'CoverResamplingRegressionTest'):
    output = run([java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '--enable-native-access=ALL-UNNAMED', f'-Dskiko.library.path={b.DEFAULT_SPW_DIR / "app"}', '-cp', cp,
        f'{name}Kt', str(work / 'visual')], name)
    print(output.strip(), flush=True)
b.package(classes)
run(['py', '-3.12', 'tests/spmod-structure.py', str(package)], 'package-structure')
if previous_digest is not None:
    assert hashlib.sha256(previous.read_bytes()).hexdigest() == previous_digest
print('PASS cover checks and package structure; previous package unchanged')
print('Visual comparison:', work / 'visual/comparison.png')
print('LIMIT: synthetic artwork only; real cover appearance needs user review')
