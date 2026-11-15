"""One build and focused cold-open/data regressions. Uses synthetic state; never launches SPW."""
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
package = b.BUILD / 'libs' / b.PACKAGE_FILENAME
assert not package.exists(), f'Refusing to overwrite {package}'
previous = b.BUILD / 'libs/SPW-Stats-v0.5.1-dev.1.spmod'
previous_digest = hashlib.sha256(previous.read_bytes()).hexdigest() if previous.exists() else None
work = b.BUILD / 'tests' / f'first-open-{b.PLUGIN_VERSION}'
work.mkdir(parents=True, exist_ok=True)
run_root = Path(tempfile.mkdtemp(prefix='validation-', dir=work))
classes = b.BUILD / 'plugin-classes'
assert classes.resolve().is_relative_to(b.BUILD.resolve()) and classes.resolve() != b.BUILD.resolve()
jdk, compiler = b.find_jdk(), b.find_kotlinc()
kotlin = compiler.parent.parent
java = str(jdk / 'bin/java.exe')


def run(command, name, state=None):
    environment = os.environ.copy()
    isolated = state or run_root / name
    environment['APPDATA'] = str(isolated / 'appdata')
    environment['LOCALAPPDATA'] = str(isolated / 'localappdata')
    log = run_root / f'{name}.log'
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=root, env=environment, stdout=stream, stderr=subprocess.STDOUT)
    output = log.read_text(encoding='utf-8', errors='replace')
    print(name, result.returncode, flush=True)
    if result.returncode:
        print(output[-7500:])
        raise SystemExit(result.returncode)
    return output


run(['git', 'diff', '--check'], 'diff-check')
b.compile_kotlin(jdk, compiler, b.ensure_host_jar(b.DEFAULT_SPW_DIR), classes)
fixture = run_root / 'fixtures'
fixture.mkdir()
cp = os.pathsep.join(map(str, [fixture, kotlin / 'lib/kotlin-stdlib.jar', classes,
    b.TOOLS / 'spw-compose-compile-index.jar', b.TOOLS / 'spw-host.jar', b.TOOLS / 'sqlite-jdbc.jar']))
names = ['FirstOpenBenchmark', 'FirstOpenRegressionTest', 'DataRegressionTest', 'DataIntegrityRegressionTest',
    'StatsStoreCacheRegressionTest', 'NavigationRegressionTest', 'NavigationTestSupport', 'SnapshotRegressionTest',
    'RankingRegressionTest']
run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', cp, '-jvm-target', '25', '-d', str(fixture),
    *[str(root / 'tests' / f'{name}.kt') for name in names]], 'compile-regressions')
vm = [java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Duser.timezone=UTC',
    f'-Dorg.sqlite.tmpdir={run_root}', '-cp', cp]
for name in ('FirstOpenRegressionTest', 'DataRegressionTest', 'DataIntegrityRegressionTest',
             'StatsStoreCacheRegressionTest', 'NavigationRegressionTest', 'RankingRegressionTest'):
    state = run_root / name
    output = run([*vm, f'{name}Kt', str(state)], name, state)
    print('\n'.join(line for line in output.splitlines() if line.startswith('PASS ') or 'regression groups passed' in line), flush=True)
for scenario in ('host', 'imported'):
    state = run_root / f'candidate-{scenario}'
    output = run([*vm, 'FirstOpenBenchmarkKt', str(state), '50000', scenario], f'candidate-{scenario}', state)
    print('\n'.join(line for line in output.splitlines() if line.startswith(('COLD ', 'STAGE ', 'COUNTERS '))), flush=True)
    baseline = work / f'baseline-{scenario}.log'
    if baseline.exists():
        pattern = r'COLD .*wall_ms=([\d.]+) allocated_mb=([\d.]+)'
        before = re.search(pattern, baseline.read_text(encoding='utf-8'))
        after = re.search(pattern, output)
        if before and after:
            print(f'COMPARISON {scenario}: cold_ms {before[1]} -> {after[1]}; allocated_mb {before[2]} -> {after[2]}')
b.package(classes)
run(['py', '-3.12', 'tests/spmod-structure.py', str(package)], 'package-structure')
if previous_digest is not None:
    assert hashlib.sha256(previous.read_bytes()).hexdigest() == previous_digest
print('PASS one verification round; previous development package unchanged')
print('LIMIT: cold snapshot benchmark excludes live-host UI rendering and interaction')
print('logs', run_root)
