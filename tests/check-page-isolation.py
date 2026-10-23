"""Render synthetic plugin switching with the real containing-Layer bridge."""
import importlib.util
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
scratch = root / 'build/tests/page-isolation'
scratch.mkdir(parents=True, exist_ok=True)
classpath = os.pathsep.join(map(str, [kotlin / 'lib/kotlin-stdlib.jar', root / 'build/plugin-classes',
    root / 'build/tools/spw-compose-compile-index.jar', *builder.saltui_jars(), root / 'build/tools/spw-host.jar']))
java = str(jdk / 'bin/java.exe')
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', classpath, f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}',
    f'-Xfriend-paths={root / "build/plugin-classes"}',
    '-jvm-target', '25', '-d', str(scratch), str(root / 'tests/MaterialLayerVisualCheck.kt')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
native_temp = scratch / 'native-temp'
native_temp.mkdir(exist_ok=True)
subprocess.run([java, f'-Dskiko.library.path={builder.DEFAULT_SPW_DIR / "app"}',
    f'-Djna.tmpdir={native_temp}', f'-Djava.io.tmpdir={native_temp}', '--enable-native-access=ALL-UNNAMED',
    '-cp', str(scratch) + os.pathsep + classpath, 'MaterialLayerVisualCheckKt',
    str(root / 'build/screenshots/page-isolation')], check=True, env=environment)
