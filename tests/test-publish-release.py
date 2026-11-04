"""Synthetic publisher checks: no GitHub requests or real package modifications."""
import hashlib
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('publisher', root / 'tools/publish_release.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
COMMIT = '1' * 40


class FakeGitHub:
    def __init__(self):
        self.release = None
        self.ref = None
        self.assets = []
        self.writes = []

    def optional(self, path):
        return self.release if path.startswith('releases/') else self.ref

    def request(self, method, path, body=None):
        if method == 'GET':
            return self.assets
        self.writes.append((method, path, body))
        if method == 'POST':
            self.release = dict(body, id=42, author={'login': publisher.BOT})
        else:
            self.release.update(body)
            self.release['html_url'] = 'https://github.com/Jethuit/SPW-Listen-Stats/releases/tag/v1.2.3'
        return self.release

    def upload(self, release_id, name, data):
        self.writes.append(('UPLOAD', name))
        asset = dict(name=name, state='uploaded', size=len(data), digest='sha256:' + hashlib.sha256(data).hexdigest())
        self.assets.append(asset)
        return asset


class PublisherTests(unittest.TestCase):
    def setUp(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr('classes/META-INF/MANIFEST.MF',
                'Plugin-Version: 1.2.3\r\nPlugin-Id: com.jethuit.spw.listenstats\r\n\r\n')
        self.data = output.getvalue()
        self.request = dict(version='1.2.3', package_size=len(self.data), package_sha256=hashlib.sha256(self.data).hexdigest())
        self.notes = '# Synthetic release'
        self.client = FakeGitHub()

    def run_publish(self):
        return publisher.publish(self.client, self.request, self.data, self.notes, COMMIT)

    def test_new_release_uploads_both_assets_before_publication(self):
        url = self.run_publish()
        self.assertTrue(url.endswith('/v1.2.3'))
        self.assertEqual([write[0] for write in self.client.writes], ['POST', 'UPLOAD', 'UPLOAD', 'PATCH'])
        self.assertFalse(self.client.release['draft'])
        self.assertEqual(self.client.release['target_commitish'], COMMIT)

    def test_published_release_is_never_modified(self):
        self.client.release = dict(draft=False)
        with self.assertRaisesRegex(ValueError, 'refusing to change'):
            self.run_publish()
        self.assertEqual(self.client.writes, [])

    def test_existing_tag_is_never_reused_or_moved(self):
        self.client.ref = {'object': {'sha': COMMIT, 'type': 'commit'}}
        with self.assertRaisesRegex(ValueError, 'Tag already exists'):
            self.run_publish()
        self.assertEqual(self.client.writes, [])

    def test_checksum_and_manifest_failures_prevent_writes(self):
        self.request['package_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.run_publish()
        self.request['package_sha256'] = hashlib.sha256(self.data).hexdigest()
        self.request['version'] = '1.2.4'
        with self.assertRaisesRegex(ValueError, 'manifest'):
            self.run_publish()
        self.assertEqual(self.client.writes, [])

    def test_own_interrupted_draft_resumes_without_replacing_assets(self):
        self.client.release = dict(id=42, draft=True, author={'login': publisher.BOT},
            target_commitish=COMMIT, body=self.notes + '\n\n<!-- spw-release-sha256: ' + self.request['package_sha256'] + ' -->')
        self.client.upload(42, 'SPW-Stats-v1.2.3.spmod', self.data)
        self.client.writes.clear()
        self.run_publish()
        self.assertEqual([write[0] for write in self.client.writes], ['UPLOAD', 'PATCH'])

    def test_conflicting_draft_asset_is_not_replaced_or_published(self):
        self.client.release = dict(id=42, draft=True, author={'login': publisher.BOT},
            target_commitish=COMMIT, body=self.notes + '\n\n<!-- spw-release-sha256: ' + self.request['package_sha256'] + ' -->')
        self.client.assets = [dict(name='SPW-Stats-v1.2.3.spmod', state='uploaded', size=len(self.data), digest='sha256:wrong')]
        with self.assertRaisesRegex(ValueError, 'Asset verification failed'):
            self.run_publish()
        self.assertEqual(self.client.writes, [])


if __name__ == '__main__':
    unittest.main()
