"""Apply an explicitly requested description correction; never touch assets or tags."""
import hashlib
import json
import os
from pathlib import Path
import re

from publish_release import GitHub, REPOSITORY


def update_notes(client, request: dict, notes: str):
    version = request['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or not notes.strip():
        raise ValueError('Invalid version or empty notes')
    release = client.request('GET', f'releases/tags/v{version}')
    if release.get('draft') or release.get('prerelease'):
        raise ValueError('This correction requires an existing stable release')
    marker = f"<!-- spw-release-sha256: {request['package_sha256']} -->"
    if marker not in release.get('body', ''):
        raise ValueError('Original package identity does not match')
    filename = f'SPW-Stats-v{version}.spmod'
    package = next(asset for asset in release['assets'] if asset['name'] == filename)
    if package.get('digest') != 'sha256:' + request['package_sha256']:
        raise ValueError('Original package checksum does not match')
    body = notes.rstrip() + '\n\n' + marker
    if release['body'] == body:
        return release
    if hashlib.sha256(release['body'].encode('utf-8')).hexdigest() != request['expected_body_sha256']:
        raise ValueError('Release notes changed since review; refusing to overwrite them')
    return client.request('PATCH', f"releases/{release['id']}", {'body': body})


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise SystemExit('Run this correction only in the repository Actions workflow')
    root = Path(__file__).resolve().parent.parent
    request = json.loads((root / '.github/release-notes-request.json').read_text(encoding='utf-8'))
    version = request['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Invalid release version')
    notes = (root / 'docs/releases' / f'v{version}.md').read_text(encoding='utf-8')
    release = update_notes(GitHub(os.environ['GITHUB_TOKEN']), request, notes)
    print(f"Updated description only: {release['html_url']}")


if __name__ == '__main__':
    main()
