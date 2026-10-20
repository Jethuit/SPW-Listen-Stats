"""Compile/run deterministic data regressions against the already built plugin.

No player is launched. All persistent state and logs go into a fresh build/tests directory.
"""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
suite = sys.argv[1] if len(sys.argv) > 1 else 'data'
assert suite in ('data', 'ui', 'cover', 'navigation', 'integrity'), 'unknown regression suite'
test_name = {'data': 'DataRegressionTest', 'ui': 'UiRegressionTest', 'cover': 'CoverRegressionTest',
             'navigation': 'NavigationRegressionTest', 'integrity': 'DataIntegrityRegressionTest'}[suite]
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
classes = ROOT / 'build/plugin-classes'
assert classes.is_dir(), 'Build the plugin first'
scratch = ROOT / 'build/tests'
scratch.mkdir(parents=True, exist_ok=True)
state = scratch / (suite + '-state')
if state.exists():
    shutil.rmtree(state)
classpath = os.pathsep.join(map(str, [kotlin / 'lib/kotlin-stdlib.jar', classes,
                                    ROOT / 'build/tools/spw-host.jar', ROOT / 'build/tools/sqlite-jdbc.jar']))
java = str(jdk / 'bin/java.exe')
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-classpath', classpath, '-jvm-target', '25', '-d', str(scratch),
                str(ROOT / 'tests' / (test_name + '.kt'))], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(state / 'appdata')
environment['LOCALAPPDATA'] = str(state / 'localappdata')
subprocess.run([java, '-Duser.timezone=UTC', '-cp', str(scratch) + os.pathsep + classpath,
                test_name + 'Kt', str(state)], check=True, env=environment)
