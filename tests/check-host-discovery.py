"""One isolated discovery/regression run per cached host. Never starts or installs SPW."""
import importlib.util
import argparse
import filecmp
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--list-hosts', action='store_true', help='show verified host labels without running checks')
args = parser.parse_args()


def current_host_version(host):
    """Only attach the installed version when the cached bytes match that installation."""
    app = builder.DEFAULT_SPW_DIR / 'app'
    try:
        if filecmp.cmp(host, app / 'ffmpeg-x64.dll', shallow=False):
            version = ET.parse(app / '.jpackage.xml').getroot().findtext('app-version', '')
            if re.fullmatch(r'\d+(?:\.\d+)+(?:[-+][A-Za-z0-9.-]+)?', version):
                return version
    except (OSError, ET.ParseError):
        pass
    return 'current-cache-version-unverified'


hosts = [(current_host_version(root / 'build/tools/spw-host.jar'), 'spw-host.jar'),
         ('1.19.0', 'spw-host-1.19.0.jar')]
if args.list_hosts:
    for version, name in hosts:
        print(f'{version}: {root / "build/tools" / name}')
    raise SystemExit(0)

jdk = builder.find_jdk()
kotlin = builder.find_kotlinc().parent.parent
scratch = root / 'build/tests/host-discovery'
scratch.mkdir(parents=True, exist_ok=True)
java = str(jdk / 'bin/java.exe')
base = [kotlin / 'lib/kotlin-stdlib.jar', root / 'build/plugin-classes']
host = root / 'build/tools/spw-host.jar'
classpath = os.pathsep.join(map(str, base + [host]))
subprocess.run([java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-classpath', classpath, '-jvm-target', '25', '-d', str(scratch),
    str(root / 'tests/HostDiscoveryRegressionTest.kt')], check=True)
for version, name in hosts:
    host = root / 'build/tools' / name
    if not host.exists():
        print(f'SKIP SPW {version}: local host cache unavailable')
        continue
    environment = os.environ.copy()
    environment['APPDATA'] = str(scratch / version / 'appdata')
    environment['LOCALAPPDATA'] = str(scratch / version / 'localappdata')
    subprocess.run([java, '-Dstdout.encoding=UTF-8', '-cp',
        os.pathsep.join(map(str, [scratch, *base, host])), 'HostDiscoveryRegressionTestKt', str(host)],
        check=True, env=environment, cwd=root)
    subprocess.run(['py', '-3.12', str(root / 'tests/probe-titlebar-hook.py'),
        '--host-version', version, '--host-jar', str(host), '--before-quick'], check=True, cwd=root)
