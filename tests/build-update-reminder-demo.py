"""Build a marked update demo and a newer normal package to restore afterwards.

Only generated source copies under build/tests/ are changed. The demo uses the
real checker and host toast with synthetic metadata, never a GitHub request.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def replace_once(path: Path, before: str, after: str) -> None:
    text = path.read_text(encoding='utf-8')
    if text.count(before) != 1:
        raise SystemExit(f'Demo source adapter no longer matches: {path.name}')
    path.write_text(text.replace(before, after, 1), encoding='utf-8')


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--demo-version', default='0.3.5-dev.9.update-demo')
    parser.add_argument('--spw-dir', type=Path, default=builder.DEFAULT_SPW_DIR)
    args = parser.parse_args()
    demo = re.fullmatch(r'(\d+\.\d+\.\d+)-dev\.(\d+)\.update-demo', args.demo_version)
    normal = re.fullmatch(r'(\d+\.\d+\.\d+)-dev\.(\d+)', builder.PLUGIN_VERSION)
    if not demo or not normal or demo[1] != normal[1] or int(normal[2]) <= int(demo[2]):
        raise SystemExit('Use a marked demo version followed by a higher normal dev version')

    versions = [builder.PLUGIN_VERSION, args.demo_version]
    for version in versions:
        target = builder.BUILD / 'libs' / f'SPW-Stats-v{version}.spmod'
        if target.exists():
            raise SystemExit(f'Refusing to overwrite existing package: {target}')
    scratch = (builder.BUILD / 'tests' / f'update-demo-{args.demo_version}').resolve()
    assert scratch.is_relative_to((builder.BUILD / 'tests').resolve())
    if scratch.exists():
        raise SystemExit(f'Use a fresh demo version; workspace already exists: {scratch}')

    sources = builder.PLUGIN_SRC
    original_sources = {p: digest(p) for p in sources.rglob('*.kt')}
    previous_packages = {p: digest(p) for p in (builder.BUILD / 'libs').glob('*.spmod')}
    demo_sources = scratch / 'sources'
    shutil.copytree(sources, demo_sources)
    package_root = demo_sources / 'com/jethuit/spw/listenstats'
    updates = package_root / 'updates/Updates.kt'
    metadata = f'{{"tag_name":"v{demo[1]}","draft":false,"prerelease":false}}'
    replace_once(updates, 'val path = DataFiles.directory().resolve("update-check.json")',
        'val path = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), '
        '"spw-listenstats-update-demo-${java.util.UUID.randomUUID()}.json")'
        '.also { it.toFile().deleteOnExit() }')
    replace_once(updates, 'UpdateChecker(version, path,',
        f'UpdateChecker(version, path, fetch = {{ GitHubReleases.decode(200, """{metadata}""") }},')
    replace_once(updates, 'status.value = it },', 'status.value = it.copy(releaseUrl = ReleasesUrl) },')
    replace_once(updates, 'toast("听歌统计有新正式版本', 'toast("[更新演示] 听歌统计有新正式版本')
    replace_once(updates, 'delay(30_000)', 'delay(10_000)')
    replace_once(updates, 'delay(60_000)', 'delay(5_000)')
    replace_once(package_root / 'updates/UpdateChecker.kt',
        'const val IntervalMillis = 24 * 60 * 60 * 1_000L', 'const val IntervalMillis = 5_000L')
    replace_once(package_root / 'ui/AboutDialog.kt',
        'StatsText("关于", Modifier.weight(1f),', 'StatsText("关于（更新演示）", Modifier.weight(1f),')

    jdk, kotlin = builder.find_jdk(), builder.find_kotlinc()
    host = builder.ensure_host_jar(args.spw_dir)
    packages = []
    for version, source_root, name in [
        (versions[0], sources, builder.PLUGIN_NAME),
        (versions[1], demo_sources, '听歌统计（更新演示）'),
    ]:
        builder.PLUGIN_VERSION = version
        builder.PACKAGE_FILENAME = f'SPW-Stats-v{version}.spmod'
        builder.PLUGIN_NAME = name
        builder.PLUGIN_SRC = source_root
        classes = scratch / version / 'classes'
        assert classes.resolve().is_relative_to(scratch)
        builder.compile_kotlin(jdk, kotlin, host, classes)
        package = builder.package(classes)
        subprocess.run([sys.executable, str(ROOT / 'tests/spmod-structure.py'), str(package)], check=True)
        with zipfile.ZipFile(package) as archive:
            manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
            assert f'Plugin-Version: {version}\r\n' in manifest
            if version == args.demo_version:
                nested = [archive.read(n) for n in archive.namelist()
                          if n.startswith('classes/com/jethuit/spw/listenstats/updates/Updates') and n.endswith('.class')]
                assert any(metadata.encode('utf-8') in data for data in nested)
                assert any('[更新演示]'.encode('utf-8') in data for data in nested)
        packages.append(package)

    assert all(digest(p) == value for p, value in original_sources.items()), 'Production source changed'
    assert all(digest(p) == value for p, value in previous_packages.items()), 'Previous package changed'
    print('PASS: normal/demo compilation, metadata injection, package structure, original sources and packages preserved')
    for package in packages:
        print(package)


if __name__ == '__main__':
    main()
