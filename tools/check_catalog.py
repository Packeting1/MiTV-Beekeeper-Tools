"""不依赖 Android SDK 的元数据回归检查。"""

import json
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from modules.catalog import ANDROID, app_assets, java_source, load, properties, write_java


class CatalogChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="beekeeper-catalog-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / "modules/catalog.json"
        self.config.parent.mkdir()
        shutil.copyfile(ROOT / "modules/catalog.json", self.config)
        self.entries = json.loads(self.config.read_text(encoding="utf-8"))
        for kind in ("magisk", "xposed"):
            for path in (ROOT / "modules" / kind / "src").rglob("*"):
                if path.is_file() and (
                    path.name in ("module.prop", "scope.txt", "AndroidManifest.xml", "HookEntry.java", "flag.conf")
                    or "_shared" in path.parts
                ):
                    target = self.root / path.relative_to(ROOT)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(path, target)

    def save(self):
        self.config.write_text(json.dumps(self.entries, ensure_ascii=False), encoding="utf-8")

    def test_generated_identity_and_scope_follow_module_sources(self):
        entry = next(e for e in self.entries if e["kind"] == "xposed")
        source = self.root / "modules/xposed/src" / entry["key"]
        manifest_path = source / "AndroidManifest.xml"
        manifest = ET.parse(manifest_path)
        old_package = manifest.getroot().attrib["package"]
        manifest.getroot().set("package", "test.changed")
        manifest.getroot().find("application").set(ANDROID + "label", "ChangedLabel")
        manifest.write(manifest_path, encoding="utf-8")
        hook = source / "src/test/changed/HookEntry.java"
        hook.parent.mkdir(parents=True)
        shutil.copyfile(source / "src" / Path(*old_package.split(".")) / "HookEntry.java", hook)
        (source / "scope.txt").write_text("test.new.scope\nsystem\n", encoding="utf-8")
        loaded = load(self.root)
        changed = next(e for e in loaded if e["key"] == entry["key"])
        self.assertEqual((changed["package"], changed["label"]), ("test.changed", "ChangedLabel"))
        generated = write_java(self.root / "generated", loaded).read_text(encoding="utf-8")
        self.assertEqual(generated, java_source(loaded))
        self.assertIn('scopes.put("test.changed", new String[] {"test.new.scope", "system"})', generated)
        self.assertNotIn(old_package, generated)

    def test_id_version_and_flag_follow_module_properties(self):
        entry = next(e for e in self.entries if e.get("report_flag"))
        source = self.root / "modules/magisk/src" / entry["key"]
        props = properties(source / "module.prop")
        props.update(id="test_changed_id", version="test-version", versionCode="999")
        (source / "module.prop").write_text("".join(k + "=" + v + "\n" for k, v in props.items()), encoding="utf-8")
        (source / "common/flag.conf").write_text("KEY=test_new_flag\n", encoding="utf-8")
        loaded = load(self.root)
        changed = next(e for e in loaded if e["key"] == entry["key"])
        self.assertEqual(changed["version"], "test-version")
        self.assertIn('flags.put("test_changed_id", "test_new_flag")', java_source(loaded))

    def test_duplicate_keys_assets_identities_and_outputs_are_rejected(self):
        for field in ("key", "asset", "archive_prefix"):
            with self.subTest(field=field):
                original = self.config.read_text(encoding="utf-8")
                entries = json.loads(original)
                candidates = [e for e in entries if e["kind"] == "magisk"]
                candidates[1][field] = candidates[0][field]
                self.config.write_text(json.dumps(entries), encoding="utf-8")
                with self.assertRaises(ValueError):
                    load(self.root)
                self.config.write_text(original, encoding="utf-8")
        mods = [e for e in self.entries if e["kind"] == "magisk"]
        a, b = [self.root / "modules/magisk/src" / e["key"] / "module.prop" for e in mods[:2]]
        values = properties(b)
        values["id"] = properties(a)["id"]
        b.write_text("".join(k + "=" + v + "\n" for k, v in values.items()), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "identity"):
            load(self.root)

    def test_unknown_kind_reference_and_unlisted_directory_are_rejected(self):
        original = self.config.read_text(encoding="utf-8")
        self.entries[0]["kind"] = "unknown"
        self.save()
        with self.assertRaisesRegex(ValueError, "kind"):
            load(self.root)
        self.entries = json.loads(original)
        self.entries[0]["companion"] = "not-a-module"
        self.save()
        with self.assertRaisesRegex(ValueError, "reference"):
            load(self.root)
        self.config.write_text(original, encoding="utf-8")
        unexpected = self.root / "modules/magisk/src/unlisted/module.prop"
        unexpected.parent.mkdir()
        unexpected.write_text("id=test\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source directories"):
            load(self.root)

    def test_artifact_resolution_rejects_missing_and_stale_archives(self):
        entries = load(self.root)
        xposed, magisk, bundled = [self.root / p for p in ("xposed", "magisk", "bundled")]
        for directory in (xposed, magisk, bundled):
            directory.mkdir()
        expected = {}
        for entry in entries:
            asset = entry.get("asset")
            if not asset:
                continue
            if entry["kind"] == "xposed":
                path = xposed / (entry["label"] + ".apk")
            elif entry["kind"] == "magisk":
                path = magisk / (entry["archive_prefix"] + entry["version"] + ".zip")
            else:
                path = bundled / entry["bundled"]
            path.touch()
            expected[asset] = path
        self.assertEqual(dict((asset, path) for path, asset in app_assets(entries, xposed, magisk, bundled)), expected)
        first = next(e for e in entries if e["kind"] == "magisk")
        stale = magisk / (first["archive_prefix"] + "stale.zip")
        stale.touch()
        with self.assertRaisesRegex(ValueError, "exactly"):
            app_assets(entries, xposed, magisk, bundled)
        stale.unlink()
        (xposed / (next(e for e in entries if e["kind"] == "xposed")["label"] + ".apk")).unlink()
        with self.assertRaisesRegex(ValueError, "missing module artifact"):
            app_assets(entries, xposed, magisk, bundled)


if __name__ == "__main__":
    unittest.main(verbosity=2)
