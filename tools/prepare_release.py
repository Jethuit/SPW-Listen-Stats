"""Collect the built plugin under build/releases/."""
from __future__ import annotations

import importlib.util
import hashlib
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

def main() -> None:
    filename = builder.PACKAGE_FILENAME
    source = ROOT / 'build/libs' / filename
    if not source.is_file():
        raise SystemExit('Build the current plugin version first: py -3.12 tools/build_plugin.py')
    with zipfile.ZipFile(source) as archive:
        manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8')
        if f'Plugin-Version: {builder.PLUGIN_VERSION}' not in manifest.splitlines():
            raise SystemExit('Package manifest does not match the current version; rebuild first.')
    output = ROOT / 'build/releases' / f'v{builder.PLUGIN_VERSION}'
    if output.exists():
        raise SystemExit(f'Release directory already exists; preserve its assets: {output}')
    output.mkdir(parents=True, exist_ok=False)
    target = output / filename
    shutil.copy2(source, target)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / 'SHA256SUMS.txt').write_text(f'{checksum}  {filename}\n', encoding='ascii')
    notes = ROOT / 'docs/releases' / f'v{builder.PLUGIN_VERSION}.md'
    if notes.is_file():
        shutil.copy2(notes, output / 'RELEASE_NOTES.md')
    print(f'Release assets: {output}')
    print(f'Package: {filename}')

if __name__ == '__main__':
    main()
