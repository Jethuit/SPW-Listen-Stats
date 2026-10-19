"""Collect the built plugin and its SHA-256 checksum under build/releases/."""
from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

def main() -> None:
    filename = f'plugin-{builder.PLUGIN_ID}-{builder.PLUGIN_VERSION}.spmod'
    source = ROOT / 'build/libs' / filename
    if not source.is_file():
        raise SystemExit('Build the current plugin version first: py -3.12 tools/build_plugin.py')
    with zipfile.ZipFile(source) as archive:
        manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8')
        if f'Plugin-Version: {builder.PLUGIN_VERSION}' not in manifest.splitlines():
            raise SystemExit('Package manifest does not match the current version; rebuild first.')
    output = ROOT / 'build/releases' / f'v{builder.PLUGIN_VERSION}'
    output.mkdir(parents=True, exist_ok=True)
    target = output / filename
    shutil.copy2(source, target)
    with target.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    (output / 'SHA256SUMS.txt').write_text(f'{digest}  {filename}\n', encoding='ascii')
    print(f'Release assets: {output}')
    print(f'SHA256 {digest}  {filename}')

if __name__ == '__main__':
    main()
