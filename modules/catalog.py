"""模块关联清单；身份、版本和作用域沿用各模块源码中的定义。"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


def properties(path):
    return dict(
        line.split("=", 1)
        for line in path.read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.startswith("#")
    )


def load(root=ROOT):
    entries = json.loads((root / "modules/catalog.json").read_text(encoding="utf-8"))
    keys, identities, assets, outputs = set(), set(), set(), set()
    for entry in entries:
        key, kind = entry["key"], entry["kind"]
        if key in keys or not re.fullmatch(r"[a-zA-Z0-9_-]+", key):
            raise ValueError("duplicate or invalid module key: " + key)
        keys.add(key)
        if kind not in ("magisk", "xposed", "external"):
            raise ValueError("unknown module kind: " + kind)
        if kind != "external":
            source = root / "modules" / kind / "src" / key
            entry["source"] = source
            if kind == "xposed":
                manifest = ET.parse(source / "AndroidManifest.xml").getroot()
                entry["package"] = manifest.attrib["package"]
                entry["label"] = manifest.find("application").attrib[ANDROID + "label"]
                output = "xposed/" + entry["label"] + ".apk"
                entry["scopes"] = [
                    s.strip() for s in (source / "scope.txt").read_text(encoding="utf-8").splitlines() if s.strip()
                ]
                if not entry["scopes"] or len(entry["scopes"]) != len(set(entry["scopes"])):
                    raise ValueError("empty or duplicate scope: " + key)
                package_source = source / "src" / Path(*entry["package"].split(".")) / "HookEntry.java"
                if not package_source.is_file():
                    raise ValueError("missing entry class: " + key)
            else:
                prop = properties(source / "module.prop")
                entry["id"], entry["version"] = prop["id"], prop["version"]
                int(prop["versionCode"])
                prefix = entry["archive_prefix"]
                if not re.fullmatch(r"[a-zA-Z0-9_-]+", prefix):
                    raise ValueError("invalid archive prefix: " + key)
                output = "magisk/" + prefix
                if entry.get("report_flag"):
                    entry["flag"] = properties(source / "common/flag.conf")["KEY"]
                for shared in entry.get("shared", []):
                    if not (root / "modules/magisk/src/_shared" / shared).is_dir():
                        raise ValueError("missing shared directory: " + shared)
            if output in outputs:
                raise ValueError("duplicate build output: " + output)
            outputs.add(output)
        ident = entry.get("package", entry.get("id"))
        if not ident or ident in identities:
            raise ValueError("duplicate or missing module identity: " + key)
        identities.add(ident)
        asset = entry.get("asset")
        if asset:
            suffix = ".apk" if kind == "xposed" else ".zip"
            if asset in assets or not re.fullmatch(r"[a-zA-Z0-9_.-]+", asset) or not asset.endswith(suffix):
                raise ValueError("duplicate or invalid asset: " + asset)
            assets.add(asset)
        ui = entry.get("ui")
        if ui and not all(isinstance(ui.get(field), str) for field in ("title", "desc")):
            raise ValueError("missing UI title or description: " + key)
        if ui and not ui.get("zygisk") and not asset:
            raise ValueError("missing UI module asset: " + key)
    by_key = {e["key"]: e for e in entries}
    for entry in entries:
        refs = list(entry.get("embed", {}).values())
        if entry.get("companion"):
            refs.append(entry["companion"])
        for ref in refs:
            if ref not in by_key or by_key[ref]["kind"] != "xposed":
                raise ValueError("unknown Xposed reference: " + ref)
        if entry.get("companion") and not by_key[entry["companion"]].get("asset"):
            raise ValueError("missing companion asset: " + entry["companion"])
        ui = entry.get("ui", {})
        if ui.get("requires") and ui["requires"] not in by_key:
            raise ValueError("unknown dependency: " + ui["requires"])
    for kind, filename in (("magisk", "module.prop"), ("xposed", "AndroidManifest.xml")):
        actual = {p.parent.name for p in (root / "modules" / kind / "src").glob("*/" + filename)}
        declared = {e["key"] for e in entries if e["kind"] == kind}
        if actual != declared:
            raise ValueError("%s catalog differs from source directories: %s" % (kind, sorted(actual ^ declared)))
    return entries


def java_source(entries):
    by_key = {e["key"]: e for e in entries}

    def quote(value):
        return json.dumps(value, ensure_ascii=False)

    groups = []
    for entry in entries:
        ui = entry.get("ui")
        if not ui:
            continue
        args = [quote(ui["title"]), quote(ui["desc"]), quote(ui.get("warn"))]
        if entry["kind"] == "xposed":
            group = "Group.apk(%s)" % ", ".join(args + [quote(entry["package"]), quote(entry["asset"])])
        else:
            refs = ([entry["companion"]] if entry.get("companion") else []) + list(entry.get("embed", {}).values())
            packages = list(dict.fromkeys(by_key[ref]["package"] for ref in refs))
            group = "new Group(KIND_MOD, %s).mod(%s, %s)" % (
                ", ".join(args + [quote(p) for p in packages]),
                quote(entry["id"]),
                quote(entry.get("asset")),
            )
            if entry.get("companion"):
                group += ".companion(%s)" % quote(by_key[entry["companion"]]["asset"])
        for field, call in (("fix", "fix"), ("base", "base"), ("zygisk", "zygisk"), ("fw_only", "fwOnly")):
            if ui.get(field):
                group += "." + call + "()"
        if entry.get("update_asset"):
            group += ".source(%s, %s)" % (quote(entry["download"]["repo"]), quote(entry["update_asset"]))
        if ui.get("requires"):
            group += ".requires(%s, %s)" % (quote(by_key[ui["requires"]]["id"]), quote(ui["requires_msg"]))
        for field, call in (("confirm", "confirm"), ("confirm_off", "confirmOff")):
            if ui.get(field):
                group += ".%s(%s)" % (call, quote(ui[field]))
        groups.append("      " + group + ",")
    lines = [
        "package com.beekeeper.slim;",
        "",
        "import static com.beekeeper.slim.Catalog.*;",
        "import java.util.LinkedHashMap;",
        "import java.util.Map;",
        "",
        "// 由 modules/catalog.json 和模块源码生成，不在源码目录中维护副本。",
        "final class ModuleCatalog {",
        "  private ModuleCatalog() {}",
        "",
        "  static Group[] groups() {",
        "    return new Group[] {",
        *groups,
        "    };",
        "  }",
        "",
        "  static Map<String, String[]> scopes() {",
        "    Map<String, String[]> scopes = new LinkedHashMap<String, String[]>();",
    ]
    for entry in entries:
        if entry["kind"] == "xposed":
            lines.append(
                "    scopes.put(%s, new String[] {%s});"
                % (quote(entry["package"]), ", ".join(quote(s) for s in entry["scopes"]))
            )
    lines += [
        "    return scopes;",
        "  }",
        "",
        "  static Map<String, String> flags() {",
        "    Map<String, String> flags = new LinkedHashMap<String, String>();",
    ]
    for entry in entries:
        if entry.get("flag"):
            lines.append("    flags.put(%s, %s);" % (quote(entry["id"]), quote(entry["flag"])))
    return "\n".join(lines + ["    return flags;", "  }", "}", ""])


def write_java(directory, entries):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "ModuleCatalog.java"
    path.write_text(java_source(entries), encoding="utf-8", newline="\n")
    return path


def app_assets(entries, xposed_dist, magisk_dist, bundled):
    assets = []
    for entry in entries:
        asset = entry.get("asset")
        if not asset:
            continue
        if entry["kind"] == "xposed":
            path = xposed_dist / (entry["label"] + ".apk")
        elif entry["kind"] == "magisk":
            prefix = entry["archive_prefix"]
            found = sorted(magisk_dist.glob(prefix + "*.zip"))
            path = magisk_dist / (prefix + entry["version"] + ".zip")
            if found != [path]:
                raise ValueError("expected exactly the declared archive for " + entry["key"])
        else:
            path = bundled / entry["bundled"]
        if not path.is_file():
            raise ValueError("missing module artifact: " + str(path))
        assets.append((path, asset))
    return assets


if __name__ == "__main__":
    try:
        entries = load()
    except (ValueError, KeyError, OSError, ET.ParseError) as e:
        sys.exit(str(e))
    print("ok: %d module records" % len(entries))
