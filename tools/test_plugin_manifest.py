# SPDX-License-Identifier: Apache-2.0
"""Check release overrides, preservation of the development manifest, and its shape."""
import json
from pathlib import Path
import tempfile
import unittest

from plugin_manifest import build_manifest


class BuildManifestTest(unittest.TestCase):
    def test_release_version_overrides_source_without_editing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'kiosk-satellite-plugin.json'
            original = b'{"id":"demo","version":"1.0.1","apiVersion":1,"description":"Demo","settings":[]}'
            path.write_bytes(original)
            self.assertEqual(build_manifest(path), original)
            release = json.loads(build_manifest(path, '1.2.0'))
            self.assertEqual(release, {**json.loads(original), 'version': '1.2.0'})
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(json.loads(build_manifest(path, '1.3.0-beta.1'))['version'], '1.3.0-beta.1')

    def test_invalid_versions_cannot_become_package_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'kiosk-satellite-plugin.json'
            path.write_text('{"version":"1.0.1"}')
            for version in ['', 'v1.2.0', '1.2', '../1.2.0', '1.2.0/other', '1.2.0\n', '1.2.0-' + 'a' * 40]:
                with self.subTest(version=version), self.assertRaises(ValueError):
                    build_manifest(path, version)


class DevelopmentManifestShapeTest(unittest.TestCase):
    """The shipped manifest's shape: Logging level anchors the hidden state settings."""

    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / 'kiosk-satellite-plugin.json'
        cls.manifest = json.loads(path.read_text())

    def test_version(self):
        self.assertEqual(self.manifest['version'], '1.0.0')

    def test_settings_order_and_anchor(self):
        settings = self.manifest['settings']
        self.assertEqual([setting['key'] for setting in settings],
                         ['tasks', 'loggingLevel', 'sessionPid', 'runState', 'schedulerState'])
        level = settings[1]
        self.assertEqual(level['type'], 'select')
        self.assertEqual(level['title'], 'Logging level')
        self.assertEqual(level['options'], ['Informational', 'Verbose'])
        self.assertEqual(level['default'], 'Informational')
        self.assertEqual(level['group'], 'Advanced')
        # Every managed state setting hides on the Logging level
        # anchor, which is declared before all of them.
        for state in settings[2:]:
            self.assertEqual(state['group'], 'Advanced')
            self.assertEqual(state['visibleWhen'], {'key': 'loggingLevel', 'equals': '__never__'})

    def test_groups_and_buttons(self):
        self.assertEqual([group['title'] for group in self.manifest['groups']], ['Tasks', 'Advanced'])
        self.assertEqual(self.manifest['groups'][0]['readings'],
                         ['text_sensor.status', 'text_sensor.last_run', 'text_sensor.next_run'])
        self.assertEqual(self.manifest['settings'][0]['group'], 'Tasks')
        self.assertEqual([button['id'] for button in self.manifest['buttons']],
                         ['runNow', 'clearSchedulerState'])
        for button in self.manifest['buttons']:
            self.assertEqual(button['group'], 'Advanced')


if __name__ == '__main__':
    unittest.main()
