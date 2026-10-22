"""Targeted entry check: host resolution, native rendering, real pointer clicks and package."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
scratch = root / 'build/tests/titlebar-entry'
scratch.mkdir(parents=True, exist_ok=True)
subprocess.run([sys.executable, str(root / 'tests/probe-titlebar-hook.py'), '--before-quick'], check=True)
classpath = os.pathsep.join(map(str, [kotlin / 'lib/kotlin-stdlib.jar', root / 'build/plugin-classes',
                                   root / 'build/tools/spw-compose-compile-index.jar',
                                   *builder.saltui_jars(), root / 'build/tools/spw-host.jar']))
java = str(jdk / 'bin/java.exe')
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', classpath, f'-Xplugin={kotlin / "lib/compose-compiler-plugin.jar"}',
    '-jvm-target', '25', '-d', str(scratch), str(root / 'tests/TitlebarEntryVisualCheck.kt'),
    str(root / 'tests/NavigationTestSupport.kt')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
native_temp = scratch / 'native-temp'
native_temp.mkdir(exist_ok=True)
output = root / 'build/screenshots/titlebar-entry'
subprocess.run([java, f'-Dskiko.library.path={builder.DEFAULT_SPW_DIR / "app"}',
    f'-Djna.tmpdir={native_temp}', f'-Djava.io.tmpdir={native_temp}', '--enable-native-access=ALL-UNNAMED',
    '-cp', str(scratch) + os.pathsep + classpath, 'TitlebarEntryVisualCheckKt', str(output)],
    check=True, env=environment)
with zipfile.ZipFile(root / 'build/libs' / builder.PACKAGE_FILENAME) as package:
    manifest = package.read('classes/META-INF/MANIFEST.MF').decode('utf-8')
    assert f'Plugin-Version: {builder.PLUGIN_VERSION}' in manifest
    assert 'Plugin-Permissions: library-read,class-transform' in manifest
    resource = 'com/jethuit/spw/listenstats/icons/stats-entry.svg'
    original = (builder.RESOURCES / resource).read_bytes()
    supplied = root / 'preview/ai_studio_code (2).html'
    if supplied.exists():
        assert original == supplied.read_bytes()
    assert package.read('classes/' + resource) == original
print('PASS package version, unchanged permissions and original SVG bytes')
