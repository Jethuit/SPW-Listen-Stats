"""Check that a description correction cannot overwrite concurrent edits or assets."""
import copy
import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'tools'))
from update_release_notes import update_notes


class FakeGitHub:
    def __init__(self):
        self.release = dict(id=42, draft=False, prerelease=False,
            body='Old notes\n\n<!-- spw-release-sha256: ' + 'a' * 64 + ' -->',
            assets=[dict(id=8, name='SPW-Stats-v1.2.3.spmod', digest='sha256:' + 'a' * 64)])
        self.writes = []

    def request(self, method, path, body=None):
        if method == 'PATCH':
            self.writes.append((path, body))
            self.release.update(body)
        return self.release


class NotesTests(unittest.TestCase):
    def setUp(self):
        self.client = FakeGitHub()
        self.request = dict(version='1.2.3', package_sha256='a' * 64,
            expected_body_sha256=hashlib.sha256(self.client.release['body'].encode()).hexdigest())

    def test_only_body_is_sent_and_assets_are_preserved(self):
        assets = copy.deepcopy(self.client.release['assets'])
        update_notes(self.client, self.request, '# New notes')
        self.assertEqual(len(self.client.writes), 1)
        path, payload = self.client.writes[0]
        self.assertEqual(path, 'releases/42')
        self.assertEqual(set(payload), {'body'})
        self.assertEqual(self.client.release['assets'], assets)
        self.assertFalse(self.client.release['draft'])
        update_notes(self.client, self.request, '# New notes')
        self.assertEqual(len(self.client.writes), 1)

    def test_concurrent_edits_are_not_overwritten(self):
        self.client.release['body'] = 'Manual correction\n\n<!-- spw-release-sha256: ' + 'a' * 64 + ' -->'
        with self.assertRaisesRegex(ValueError, 'changed since review'):
            update_notes(self.client, self.request, 'New notes')
        self.assertEqual(self.client.writes, [])

    def test_wrong_package_is_not_updated(self):
        self.client.release['assets'][0]['digest'] = 'sha256:' + 'b' * 64
        with self.assertRaisesRegex(ValueError, 'checksum does not match'):
            update_notes(self.client, self.request, 'New notes')
        self.assertEqual(self.client.writes, [])


if __name__ == '__main__':
    unittest.main()
