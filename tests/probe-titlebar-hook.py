"""Resolve structurally discovered page/material/titlebar hooks in an isolated host JVM."""
import importlib.util
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--before-quick', action='store_true')
parser.add_argument('--host-version', default='1.19.1')
parser.add_argument('--host-jar', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
jdk = builder.find_jdk()
scratch = root / 'build/tests/titlebar-hook'
scratch.mkdir(parents=True, exist_ok=True)
host = args.host_jar or root / 'build/tools/spw-host.jar'
classpath = os.pathsep.join(map(str, [root / 'build/plugin-classes', host]))
subprocess.run([str(jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath,
                '-d', str(scratch), str(root / 'tests/TitlebarHookProbe.java')], check=True)
environment = os.environ.copy()
environment['APPDATA'] = str(scratch / 'appdata')
environment['LOCALAPPDATA'] = str(scratch / 'localappdata')
subprocess.run([str(jdk / 'bin/java.exe'), '-cp', str(scratch) + os.pathsep + classpath,
                'TitlebarHookProbe', args.host_version, str(host.resolve()), *(['--before-quick'] if args.before_quick else [])],
               check=True, env=environment)
