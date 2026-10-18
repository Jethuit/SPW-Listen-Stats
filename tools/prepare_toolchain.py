#!/usr/bin/env python3
"""Download the local build toolchain used by tools/build_plugin.py.

Nothing here is committed: JDK 25, Kotlin 2.4.20, sqlite-jdbc and the host fat
jar are cached under build/tools. Re-run after cleaning build/ or after the
player updates.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / 'build' / 'tools'
DEFAULT_SPW_DIR = Path(r'E:\SteamLibrary\steamapps\common\Salt Player for Windows')
KOTLIN_VERSION = '2.4.20'
SQLITE_VERSION = '3.50.3.0'
SALTUI_VERSION = '3.0.0-beta02'
JDK_MAJOR = '25'


def opener() -> urllib.request.OpenerDirector:
    instance = urllib.request.build_opener()
    instance.addheaders = [('User-Agent', 'Mozilla/5.0')]
    return instance


def download(url: str, target: Path) -> None:
    if target.exists() and target.stat().st_size > 0:
        print('cached', target)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    print('download', url)
    with opener().open(url, timeout=180) as response, open(target.with_suffix(target.suffix + '.part'), 'wb') as handle:
        total = int(response.headers.get('Content-Length') or 0)
        done = 0
        while True:
            chunk = response.read(1024 * 512)
            if not chunk:
                break
            handle.write(chunk)
            done += len(chunk)
            if total and done % (1024 * 1024 * 20) < 1024 * 512:
                print(f'  {done * 100 // total}%')
    target.with_suffix(target.suffix + '.part').replace(target)


def extract(zip_path: Path, target: Path, strip: str | None = None) -> None:
    if target.exists():
        print('cached', target)
        return
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        for member in archive.infolist():
            name = member.filename
            if strip and name.startswith(strip):
                name = name[len(strip):]
            if not name:
                continue
            destination = target / name
            if member.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as source, open(destination, 'wb') as output:
                    shutil.copyfileobj(source, output)


def jdk() -> None:
    target = TOOLS / 'jdk25'
    if any(target.glob('*/bin/javac.exe')):
        print('cached', target)
        return
    api = (
        'https://api.adoptium.net/v3/assets/latest/'
        f'{JDK_MAJOR}/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    )
    with opener().open(api, timeout=60) as response:
        assets = json.load(response)
    link = assets[0]['binary']['package']['link']
    archive = TOOLS / 'jdk25.zip'
    download(link, archive)
    extract(archive, target)


def kotlin() -> None:
    target = TOOLS / 'kotlin'
    if (target / 'kotlinc' / 'bin' / 'kotlinc.bat').exists():
        print('cached', target)
        return
    archive = TOOLS / 'kotlin-compiler.zip'
    download(
        f'https://github.com/JetBrains/kotlin/releases/download/v{KOTLIN_VERSION}/kotlin-compiler-{KOTLIN_VERSION}.zip',
        archive,
    )
    extract(archive, target)


def sqlite() -> None:
    target = TOOLS / 'sqlite-jdbc.jar'
    if target.exists():
        print('cached', target)
        return
    download(
        f'https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/{SQLITE_VERSION}/'
        f'sqlite-jdbc-{SQLITE_VERSION}.jar',
        target,
    )


def saltui() -> None:
    target = TOOLS / 'saltui'
    for artifact in ('salt-ui-desktop', 'salt-core-desktop'):
        path = target / f'{artifact}-{SALTUI_VERSION}.jar'
        download(
            f'https://repo1.maven.org/maven2/io/github/moriafly/{artifact}/{SALTUI_VERSION}/'
            f'{artifact}-{SALTUI_VERSION}.jar',
            path,
        )


def host_jar(spw_dir: Path) -> None:
    source = spw_dir / 'app' / 'ffmpeg-x64.dll'
    target = TOOLS / 'spw-host.jar'
    if target.exists():
        print('cached', target)
        return
    if not source.exists():
        raise SystemExit(f'SPW payload not found: {source}')
    shutil.copyfile(source, target)
    print('copied', source, '->', target)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--spw-dir', type=Path, default=DEFAULT_SPW_DIR)
    args = parser.parse_args()
    TOOLS.mkdir(parents=True, exist_ok=True)
    jdk()
    kotlin()
    sqlite()
    saltui()
    host_jar(args.spw_dir)
    print('toolchain ready under', TOOLS)


if __name__ == '__main__':
    main()
