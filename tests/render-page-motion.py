"""One isolated Compose render pass; synthetic pages, no player settings/history."""
import importlib.util
import os
from pathlib import Path
import subprocess
import shutil

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
scratch = root / 'build/tests/page-motion'
scratch.mkdir(parents=True, exist_ok=True)
classpath = os.pathsep.join(map(str, [kotlin / 'lib/kotlin-stdlib.jar', root / 'build/plugin-classes',
                                    *builder.saltui_jars(), root / 'build/tools/spw-host.jar']))
java = str(jdk / 'bin/java.exe')
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-classpath', classpath, f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}',
                '-jvm-target', '25', '-d', str(scratch), str(root / 'tests/PageMotionVisualCheck.kt')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
temp = scratch / 'native-temp'
temp.mkdir(exist_ok=True)
output = root / 'preview/navigation-motion'
output.mkdir(parents=True, exist_ok=True)
rendered = scratch / 'rendered'
rendered.mkdir(exist_ok=True)
subprocess.run([java, f'-Dskiko.library.path={builder.DEFAULT_SPW_DIR / "app"}',
                f'-Djna.tmpdir={temp}', f'-Djava.io.tmpdir={temp}', '--enable-native-access=ALL-UNNAMED',
                '-cp', str(scratch) + os.pathsep + classpath, 'PageMotionVisualCheckKt',
                str(rendered)], env=environment, check=True)
shutil.copytree(rendered, output, dirs_exist_ok=True)
