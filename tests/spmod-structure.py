#!/usr/bin/env python3
"""Lightweight structure check for a built SPW .spmod package.

Usage: python tests/spmod-structure.py [path/to/plugin.spmod]
If no path is given, the newest build/libs/*.spmod is used.
"""

from __future__ import annotations

import glob
import json
import os
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_ID = 'com.jethuit.spw.listenstats'
PLUGIN_CLASS = 'com.jethuit.spw.listenstats.MainPlugin'


def find_package() -> Path:
    if len(sys.argv) > 1:
        return Path(sys.argv[1])
    candidates = sorted(glob.glob(str(ROOT / 'build' / 'libs' / '*.spmod')), key=os.path.getmtime)
    if not candidates:
        raise SystemExit('no .spmod found; run tools/build_plugin.py first')
    return Path(candidates[-1])


def main() -> None:
    package = find_package()
    if not package.is_file():
        raise SystemExit(f'missing package: {package}')
    with zipfile.ZipFile(package) as archive:
        names = set(archive.namelist())
        required = {
            'classes/META-INF/MANIFEST.MF',
            'classes/META-INF/extensions.idx',
            'classes/preference_config.json',
            'lib/sqlite-jdbc.jar',
            'classes/META-INF/LICENSE',
            'classes/META-INF/NOTICE',
            'classes/META-INF/licenses/Apache-2.0.txt',
            'classes/META-INF/licenses/sqlite-jdbc-zentus.txt',
        }
        missing = required - names
        assert not missing, f'missing entries: {sorted(missing)}'
        class_entry = 'classes/' + PLUGIN_CLASS.replace('.', '/') + '.class'
        assert class_entry in names, f'missing plugin class: {class_entry}'
        assert not any(n.endswith(('spw-host.jar', '.db', '.db-wal', '.db-shm'))
                       or '/jdk25/' in n or '/.references/' in n for n in names), 'private or host files in package'
        assert not any('spw-compose-compile-index' in n or 'RestoreComposeIndex' in n for n in names), 'compile-only tool in package'

        manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8')
        assert all(len(line.encode('utf-8')) <= 70 for line in manifest.splitlines()), 'manifest UTF-8 line exceeds byte budget'
        fields = {}
        for line in manifest.splitlines():
            if line.startswith(' ') and fields:
                key = next(reversed(fields))
                fields[key] += line[1:]
            elif ': ' in line:
                key, value = line.split(': ', 1)
                fields[key] = value
        assert fields.get('Plugin-Class') == PLUGIN_CLASS, fields
        assert fields.get('Plugin-Id') == PLUGIN_ID, fields
        assert fields.get('Plugin-Has-Config') == 'true', fields
        permissions = set(fields.get('Plugin-Permissions', '').split(','))
        assert {'library-read', 'class-transform'} <= permissions, permissions
        assert 'key-bindings' not in permissions, 'unused permission declared'

        index = archive.read('classes/META-INF/extensions.idx').decode('utf-8')
        assert 'com.jethuit.spw.listenstats.PlaybackExtension' in index, index
        preferences = json.loads(archive.read('classes/preference_config.json'))
        group = next(g for g in preferences['configs'] if g['config'] == 'config.json')
        assert all(p.get('key') != 'dailyHighHours' for p in group['preferences']), 'removed heatmap setting still exposed'
        settings = {p['key']: p for p in group['preferences'] if 'key' in p}
        for key, kind, default in [('refreshIntervalSeconds', 'edittext', '5'),
                                   ('detailVisibleRows', 'edittext', '6'),
                                   ('showDetailHint', 'switch', True),
                                   ('heatmapAnimations', 'switch', True)]:
            assert settings[key]['type'] == kind and settings[key]['default_value'] == default, key
        libraries = sorted(n for n in names if n.startswith('lib/') and n.endswith('.jar'))
        assert 'lib/sqlite-jdbc.jar' in libraries, libraries
        assert any(n.startswith('lib/salt-ui-desktop-') for n in libraries), libraries
        assert any(n.startswith('lib/salt-core-desktop-') for n in libraries), libraries
        print(f'OK {package.name}: {len(names)} entries, permissions={sorted(permissions)}, libs={libraries}')


if __name__ == '__main__':
    main()
