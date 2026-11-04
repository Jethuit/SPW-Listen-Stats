"""Publish an already built package using the Actions job's GITHUB_TOKEN.

The package is transferred through a Git blob, without adding it to a commit
tree. Never build again, replace assets, move tags, or edit published releases.
"""
from __future__ import annotations

import ast
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
import zipfile

REPOSITORY = 'Jethuit/SPW-Listen-Stats'
BOT = 'github-actions[bot]'


class GitHub:
    def __init__(self, token: str):
        self.token = token

    def request(self, method: str, path: str, body=None):
        url = f'https://api.github.com/repos/{REPOSITORY}/{path}'
        data = None if body is None else json.dumps(body).encode('utf-8')
        return self.send(method, url, data, 'application/json')

    def send(self, method: str, url: str, data: bytes | None, content_type: str):
        request = Request(url, data=data, method=method, headers={
            'Authorization': f'Bearer {self.token}',
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2026-03-10',
            'Content-Type': content_type,
            'User-Agent': 'SPW-Listen-Stats-release-workflow',
        })
        with urlopen(request, timeout=120) as response:
            return json.load(response)

    def optional(self, path: str):
        try:
            return self.request('GET', path)
        except HTTPError as failure:
            if failure.code == 404:
                return None
            raise

    def upload(self, release_id: int, name: str, data: bytes):
        url = f'https://uploads.github.com/repos/{REPOSITORY}/releases/{release_id}/assets?name={quote(name)}'
        return self.send('POST', url, data, 'application/octet-stream')


def validate_request(request: dict, root: Path) -> None:
    version = request.get('version', '')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Only a new stable version can be published')
    for key, length in [('package_blob_sha', 40), ('package_sha256', 64)]:
        if not re.fullmatch(rf'[0-9a-f]{{{length}}}', request.get(key, '')):
            raise ValueError(f'Invalid {key}')
    size = request.get('package_size')
    if type(size) is not int or not 0 < size <= 100_000_000:
        raise ValueError('Invalid package size')
    tree = ast.parse((root / 'tools/build_plugin.py').read_text(encoding='utf-8'))
    versions = [node.value.value for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == 'PLUGIN_VERSION'
                        for target in node.targets) and isinstance(node.value, ast.Constant)]
    if versions != [version]:
        raise ValueError('Release request and source version do not match')


def validate_package(request: dict, data: bytes) -> None:
    if len(data) != request['package_size'] or hashlib.sha256(data).hexdigest() != request['package_sha256']:
        raise ValueError('Prepared package checksum or size does not match')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = archive.read('classes/META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
        fields = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
        if fields.get('Plugin-Version') != request['version'] or fields.get('Plugin-Id') != 'com.jethuit.spw.listenstats':
            raise ValueError('Package manifest does not match the release')


def publish(client, request: dict, data: bytes, notes: str, commit: str) -> str:
    validate_package(request, data)
    if not re.fullmatch(r'[0-9a-f]{40}', commit) or not notes.strip():
        raise ValueError('Missing release commit or release notes')
    tag = f"v{request['version']}"
    release = client.optional(f'releases/tags/{tag}')
    ref = client.optional(f'git/ref/tags/{tag}')
    marker = f"<!-- spw-release-sha256: {request['package_sha256']} -->"
    body = notes.rstrip() + '\n\n' + marker
    if release is not None:
        if (not release.get('draft') or release.get('author', {}).get('login') != BOT
                or release.get('body') != body or release.get('target_commitish') != commit):
            raise ValueError('Existing release is published or is not this workflow draft; refusing to change it')
        if ref is not None and (ref['object'].get('type') != 'commit' or ref['object']['sha'] != commit):
            raise ValueError('Existing tag points elsewhere; refusing to move it')
    else:
        if ref is not None:
            raise ValueError('Tag already exists; refusing to reuse it')
        release = client.request('POST', 'releases', {
            'tag_name': tag, 'target_commitish': commit, 'name': tag,
            'body': body, 'draft': True, 'prerelease': False,
        })
    release_id = release['id']
    filename = f"SPW-Stats-v{request['version']}.spmod"
    assets = {
        filename: data,
        'SHA256SUMS.txt': f"{request['package_sha256']}  {filename}\n".encode('ascii'),
    }
    existing = client.request('GET', f'releases/{release_id}/assets?per_page=100')
    if len({asset['name'] for asset in existing}) != len(existing) or any(asset['name'] not in assets for asset in existing):
        raise ValueError('Unexpected draft assets; refusing to replace or remove them')
    by_name = {asset['name']: asset for asset in existing}
    for name, content in assets.items():
        asset = by_name.get(name)
        expected_digest = 'sha256:' + hashlib.sha256(content).hexdigest()
        if asset is None:
            asset = client.upload(release_id, name, content)
        if asset.get('state') != 'uploaded' or asset.get('size') != len(content) or asset.get('digest') != expected_digest:
            raise ValueError(f'Asset verification failed: {name}; refusing to replace it or publish the draft')
    result = client.request('PATCH', f'releases/{release_id}', {'draft': False, 'make_latest': 'true'})
    if result.get('draft') or result.get('author', {}).get('login') != BOT:
        raise ValueError('Release result does not have the expected bot author and publication state')
    return result['html_url']


def main() -> None:
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise SystemExit('Run this publisher only in the repository GitHub Actions workflow')
    root = Path(__file__).resolve().parent.parent
    request = json.loads((root / '.github/release-request.json').read_text(encoding='utf-8'))
    validate_request(request, root)
    notes = (root / 'docs/releases' / f"v{request['version']}.md").read_text(encoding='utf-8')
    client = GitHub(os.environ['GITHUB_TOKEN'])
    blob = client.request('GET', f"git/blobs/{request['package_blob_sha']}")
    if blob.get('encoding') != 'base64' or blob.get('sha') != request['package_blob_sha']:
        raise ValueError('Invalid prepared package blob')
    data = base64.b64decode(''.join(blob['content'].split()), validate=True)
    url = publish(client, request, data, notes, os.environ['RELEASE_COMMIT'])
    print(f'Published {url}')
    summary = f"Published [{request['version']}]({url}) as `{BOT}`.\n\nSHA-256: `{request['package_sha256']}`\n"
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
        stream.write(summary)


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(f'Release publication failed: {failure}', file=sys.stderr)
        raise SystemExit(1)
