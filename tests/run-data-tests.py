"""Compile/run deterministic data regressions against the already built plugin.

No player is launched. All persistent state and logs go into a fresh build/tests directory.
"""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parent.parent
suite = sys.argv[1] if len(sys.argv) > 1 else 'data'
assert suite in ('data', 'ui'), 'suite must be data or ui'
test_name = 'UiRegressionTest' if suite == 'ui' else 'DataRegressionTest'
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
classes = ROOT / 'build/plugin-classes'
assert classes.is_dir(), 'Build the plugin first'
scratch_root = ROOT / 'build/tests'
scratch_root.mkdir(parents=True, exist_ok=True)
scratch = Path(tempfile.mkdtemp(prefix='data-regression-', dir=scratch_root))
classpath = os.pathsep.join(map(str, [kotlin / 'lib/kotlin-stdlib.jar', classes,
                                    ROOT / 'build/tools/spw-host.jar', ROOT / 'build/tools/sqlite-jdbc.jar']))
java = str(jdk / 'bin/java.exe')
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-classpath', classpath, '-jvm-target', '25', '-d', str(scratch / 'classes'),
                str(ROOT / 'tests' / (test_name + '.kt'))], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
subprocess.run([java, '-Duser.timezone=UTC', '-cp', str(scratch / 'classes') + os.pathsep + classpath,
                test_name + 'Kt', str(scratch / 'state')], check=True, env=environment)
