#!/usr/bin/env python3
"""Build the 听歌统计 .spmod package without Gradle.

The host installation ships its own fat jar (app/ffmpeg-x64.dll).  Compiling
against exactly that jar keeps Kotlin, Compose and the Workshop API versions in
lockstep with the running player.  The JDK 25 and Kotlin 2.4.20 toolchains are
downloaded once by tools/prepare-toolchain.py; this script only consumes them.

Usage:
    python tools/build_plugin.py [--spw-dir PATH] [--install-root PATH]
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build'
TOOLS = BUILD / 'tools'
PLUGIN_SRC = ROOT / 'plugin' / 'src' / 'main' / 'kotlin'
RESOURCES = ROOT / 'plugin' / 'src' / 'main' / 'resources'
PLUGIN_ID = 'com.jethuit.spw.listenstats'
PLUGIN_VERSION = '0.2.0-dev.14'
PLUGIN_NAME = '听歌统计'
PACKAGE_FILENAME = f'SPW-Stats-v{PLUGIN_VERSION}.spmod'
PLUGIN_CLASS = 'com.jethuit.spw.listenstats.MainPlugin'
DEFAULT_SPW_DIR = Path(r'E:\SteamLibrary\steamapps\common\Salt Player for Windows')


def find_jdk() -> Path:
    for javac in sorted((TOOLS / 'jdk25').glob('*/bin/javac.exe')):
        return javac.parent.parent
    java_home = Path(os.environ.get('JAVA_HOME', ''))
    if java_home and (java_home / 'bin' / 'javac.exe').exists():
        return java_home
    raise SystemExit('JDK 25 not found; run tools/prepare_toolchain.py first')


def find_kotlinc() -> Path:
    candidate = TOOLS / 'kotlin' / 'kotlinc' / 'bin' / 'kotlinc.bat'
    if not candidate.exists():
        raise SystemExit('Kotlin compiler not found; run tools/prepare-toolchain.py first')
    return candidate


def ensure_host_jar(spw_dir: Path) -> Path:
    jar = TOOLS / 'spw-host.jar'
    source = spw_dir / 'app' / 'ffmpeg-x64.dll'
    if not source.exists():
        if not jar.exists():
            raise SystemExit(f'SPW payload not found: {source}')
        return jar
    if not jar.exists() or source.stat().st_size != jar.stat().st_size:
        TOOLS.mkdir(parents=True, exist_ok=True)
        print(f'copying host jar from {source}')
        shutil.copyfile(source, jar)
    return jar


def compile_kotlin(jdk: Path, kotlinc: Path, host_jar: Path, output: Path) -> None:
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    sources = sorted(str(p) for p in PLUGIN_SRC.rglob('*.kt'))
    if not sources:
        raise SystemExit('no Kotlin sources found')
    kotlin_home = kotlinc.parent.parent
    compose_plugin = kotlin_home / 'lib' / 'compose-compiler-plugin.jar'
    sqlite_jar = TOOLS / 'sqlite-jdbc.jar'
    # Put the compiler's own stdlib first. The host jar also contains a Kotlin
    # stdlib, but compiling inline functions against it trips a compiler
    # inliner bug; the compiler stdlib has the same ABI at runtime.
    stdlib_jar = kotlin_home / 'lib' / 'kotlin-stdlib.jar'
    classpath = str(stdlib_jar) + os.pathsep + os.pathsep.join(str(p) for p in saltui_jars())
    classpath += os.pathsep + str(host_jar)
    if sqlite_jar.exists():
        classpath += os.pathsep + str(sqlite_jar)
    # Invoke the compiler through java.exe: kotlinc.bat breaks when the
    # checkout path contains a space (this workspace is "E:\Code\spw status").
    command = [
        str(jdk / 'bin' / 'java.exe'),
        '-cp', str(kotlin_home / 'lib' / '*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib',
        '-classpath', classpath,
        f'-Xplugin={compose_plugin}',
        '-jvm-target', '25',
        '-d', str(output),
        *sources,
    ]
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(jdk)
    print('compiling', len(sources), 'Kotlin files')
    completed = subprocess.run(command, env=environment, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if completed.stdout.strip():
        print(completed.stdout)
    if completed.stderr.strip():
        print(completed.stderr, file=sys.stderr)
    if completed.returncode != 0:
        raise SystemExit('kotlinc failed')
    for resource in RESOURCES.rglob('*'):
        if resource.is_file():
            target = output / resource.relative_to(RESOURCES)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(resource, target)


def manifest_text() -> str:
    entries = [
        ('Manifest-Version', '1.0'),
        ('Plugin-Class', PLUGIN_CLASS),
        ('Plugin-Id', PLUGIN_ID),
        ('Plugin-Version', PLUGIN_VERSION),
        ('Plugin-Provider', 'Jethuit'),
        ('Plugin-Name', PLUGIN_NAME),
        ('Plugin-Description', 'Salt Player for Windows 的听歌时长、年度足迹与歌曲排行统计'),
        ('Plugin-Has-Config', 'true'),
        ('Plugin-Open-Source-Url', 'https://github.com/Jethuit/SPW-Listen-Stats'),
        ('Plugin-Permissions', 'library-read,class-transform'),
    ]
    lines: list[str] = []
    for key, value in entries:
        raw = f'{key}: {value}'
        # Count encoded bytes, keeping UTF-8 characters intact. Continuations
        # include their leading space in the 70-byte budget (plus CRLF).
        line = ''
        size = 0
        for char in raw:
            width = len(char.encode('utf-8'))
            if size + width > 70:
                lines.append(line)
                line, size = ' ', 1
            line += char
            size += width
        lines.append(line)
    return '\r\n'.join(lines) + '\r\n\r\n'


def package(classes: Path) -> Path:
    manifest = classes / 'META-INF' / 'MANIFEST.MF'
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(manifest_text(), encoding='utf-8', newline='')
    output_dir = BUILD / 'libs'
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / PACKAGE_FILENAME
    if target.exists():
        target.unlink()
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n')
        for path in sorted(classes.rglob('*')):
            if path.is_file():
                archive.write(path, 'classes/' + path.relative_to(classes).as_posix())
        for jar in lib_jars():
            archive.write(jar, 'lib/' + jar.name)
    print('package', target, target.stat().st_size, 'bytes')
    return target


def saltui_jars() -> list[Path]:
    directory = TOOLS / 'saltui'
    if not directory.is_dir():
        raise SystemExit('missing SaltUI jars; run tools/prepare_toolchain.py')
    jars = sorted(directory.glob('*.jar'))
    if not jars:
        raise SystemExit('missing SaltUI jars; run tools/prepare_toolchain.py')
    return jars


def lib_jars() -> list[Path]:
    result = []
    sqlite_jar = TOOLS / 'sqlite-jdbc.jar'
    if sqlite_jar.exists():
        result.append(sqlite_jar)
    result.extend(saltui_jars())
    return result


def install_tree(classes: Path) -> Path:
    install = BUILD / 'install' / 'spw-listen-stats'
    if install.exists():
        shutil.rmtree(install)
    shutil.copytree(classes, install / 'classes')
    jars = lib_jars()
    if jars:
        library = install / 'lib'
        library.mkdir(parents=True, exist_ok=True)
        for jar in jars:
            shutil.copyfile(jar, library / jar.name)
    return install


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--spw-dir', type=Path, default=DEFAULT_SPW_DIR)
    parser.add_argument('--install-root', type=Path, default=None,
                        help='copy the unpacked plugin into this PF4J plugins directory')
    args = parser.parse_args()

    jdk = find_jdk()
    kotlinc = find_kotlinc()
    host_jar = ensure_host_jar(args.spw_dir)
    classes = BUILD / 'plugin-classes'
    compile_kotlin(jdk, kotlinc, host_jar, classes)
    package_path = package(classes)
    install = install_tree(classes)
    if args.install_root:
        target = args.install_root / 'spw-listen-stats'
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(install, target)
        print('installed to', target)
    print('done', package_path)


if __name__ == '__main__':
    main()
