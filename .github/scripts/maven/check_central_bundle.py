#!/usr/bin/env python3
"""Check a Maven staging directory against Maven Central's requirements (pypackpack#99, toolchain#97).

    check_central_bundle.py <staging dir> <group> <artifact> <version> [--plugin-id <id>] [--unsigned]

Central (https://central.sonatype.org/publish/requirements/) requires, for every artifact:
the jar, `-sources.jar` and `-javadoc.jar` beside the `.pom`; an `.asc` signature and `.md5`/`.sha1`
checksums for each file; and a POM with name, description, url, a license, a developer and SCM.
`--plugin-id` also requires the Gradle plugin marker (`<id>:<id>.gradle.plugin`). `--unsigned` skips the
`.asc` check, for a rehearsal without a key. Exit 0 when everything is there; otherwise every problem is
printed and the exit is 1.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def artifact_dir(root: Path, group: str, artifact: str, version: str) -> Path:
    return root.joinpath(*group.split("."), artifact, version)


def check_files(directory: Path, artifact: str, version: str, kinds, signed: bool, problems: list):
    base = f"{artifact}-{version}"
    for kind in kinds:
        name = base + kind
        f = directory / name
        if not f.is_file():
            problems.append(f"missing {f}")
            continue
        suffixes = [".md5", ".sha1"] + ([".asc"] if signed else [])
        for suffix in suffixes:
            if not (directory / (name + suffix)).is_file():
                problems.append(f"missing {name}{suffix}")


def text(pom, path):
    node = pom.find(path, NS)
    return (node.text or "").strip() if node is not None else ""


def check_pom(pom_file: Path, problems: list):
    if not pom_file.is_file():
        return
    pom = ET.parse(pom_file).getroot()
    for path, what in [
        ("m:name", "name"),
        ("m:description", "description"),
        ("m:url", "url"),
        ("m:licenses/m:license/m:name", "license name"),
        ("m:licenses/m:license/m:url", "license url"),
        ("m:developers/m:developer/m:name", "developer name"),
        ("m:scm/m:url", "scm url"),
        ("m:scm/m:connection", "scm connection"),
    ]:
        if not text(pom, path):
            problems.append(f"{pom_file.name}: no {what}")


def main(argv):
    rest = list(argv[1:])
    signed = "--unsigned" not in rest
    plugin_id = None
    if "--plugin-id" in rest:
        i = rest.index("--plugin-id")
        plugin_id = rest[i + 1]
        del rest[i : i + 2]
    args = [a for a in rest if not a.startswith("--")]
    if len(args) != 4:
        print(__doc__)
        return 2
    root, group, artifact, version = Path(args[0]), args[1], args[2], args[3]
    problems: list = []

    main_dir = artifact_dir(root, group, artifact, version)
    check_files(main_dir, artifact, version, [".jar", "-sources.jar", "-javadoc.jar", ".pom"], signed, problems)
    check_pom(main_dir / f"{artifact}-{version}.pom", problems)

    if plugin_id:
        marker = f"{plugin_id}.gradle.plugin"
        marker_dir = artifact_dir(root, plugin_id, marker, version)
        check_files(marker_dir, marker, version, [".pom"], signed, problems)
        check_pom(marker_dir / f"{marker}-{version}.pom", problems)

    for p in problems:
        print(p)
    print("OK" if not problems else f"{len(problems)} problem(s)")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
