"""Run the read-only SPW 1.19.0 titlebar hook investigation in a separate JVM."""
import importlib.util
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
scratch = root / 'build/tests/titlebar-hook'
scratch.mkdir(parents=True, exist_ok=True)
host = root / 'build/tools/spw-host.jar'
subprocess.run([str(jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', str(host),
                '-d', str(scratch), str(root / 'tests/TitlebarHookProbe.java')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
subprocess.run([str(jdk / 'bin/java.exe'), '-cp', os.pathsep.join(map(str, [scratch, host])),
                'TitlebarHookProbe'], check=True, env=environment)
