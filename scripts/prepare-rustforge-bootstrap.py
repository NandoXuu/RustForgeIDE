#!/usr/bin/env python3
"""Post-process and statically validate the Termux-built RustForge bootstrap."""
from __future__ import annotations

import argparse
import io
import os
import posixpath
import re
import stat
import sys
import zipfile
from pathlib import PurePosixPath

APP_ID = "com.ndx.rustforge.ide"
EXPECTED_PREFIX = f"/data/data/{APP_ID}/files/usr"
OLD_PREFIXES = ("/data/data/com.termux", "/data/user/0/com.termux")
SYSTEM_LINK_ROOTS = ("/system/", "/apex/", "/vendor/", "/product/", "/odm/")
MODES_NAME = "RUSTFORGE-MODES.txt"
LINKS_NAME = "SYMLINKS.txt"


def clean_name(name: str) -> str:
    name = name.replace("\\", "/")
    if name.startswith("/"):
        raise ValueError(f"absolute archive path rejected: {name}")
    while name.startswith("./"):
        name = name[2:]
    if ".." in name.split("/"):
        raise ValueError(f"parent traversal in archive path: {name}")
    normalized = posixpath.normpath(name)
    if normalized in ("", "."):
        return ""
    if normalized == ".." or normalized.startswith("../"):
        raise ValueError(f"unsafe archive path: {name}")
    return normalized


def is_within_prefix(path: str) -> bool:
    return path not in ("..", ".") and not path.startswith("../")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with zipfile.ZipFile(args.input, "r") as zin:
        bad = zin.testzip()
        if bad:
            raise SystemExit(f"ZIP CRC validation failed at {bad}")

        entries: dict[str, tuple[zipfile.ZipInfo, bytes]] = {}
        all_paths: set[str] = set()
        for info in zin.infolist():
            name = clean_name(info.filename)
            if not name or name in (MODES_NAME,):
                continue
            if name in entries:
                raise SystemExit(f"duplicate ZIP path: {name}")
            data = zin.read(info)
            entries[name] = (info, data)
            all_paths.add(name)
            parent = posixpath.dirname(name)
            while parent and parent != ".":
                all_paths.add(parent)
                parent = posixpath.dirname(parent)

        if LINKS_NAME not in entries:
            raise SystemExit(f"{LINKS_NAME} missing from official bootstrap output")

        # Disable the upstream Termux repositories in this standalone bootstrap.
        # APT and dpkg remain installed; no package update/install is run by RustForge.
        for name in list(entries):
            if name.startswith("etc/apt/sources.list.d/"):
                del entries[name]
        if "etc/apt" not in all_paths:
            all_paths.add("etc/apt")
        source_info = zipfile.ZipInfo("etc/apt/sources.list")
        source_info.create_system = 3
        source_info.external_attr = (stat.S_IFREG | 0o600) << 16
        entries["etc/apt/sources.list"] = (
            source_info,
            b"# RustForge standalone bootstrap: no Termux upstream repository is configured.\n",
        )
        all_paths.add("etc/apt/sources.list")

        symlinks: dict[str, str] = {}
        normalized_link_records: list[str] = []
        link_text = entries[LINKS_NAME][1].decode("utf-8", "strict")
        for line in link_text.splitlines():
            if not line.strip():
                continue
            if "←" not in line:
                raise SystemExit(f"malformed symlink record: {line!r}")
            target, link_raw = line.split("←", 1)
            link = clean_name(link_raw)
            if not link:
                raise SystemExit(f"empty symlink path in record: {line!r}")
            if "\x00" in target:
                raise SystemExit(f"invalid symlink target rejected: {link} -> {target}")
            if target.startswith("/"):
                # Convert this build's app-private absolute prefix to a
                # relocatable link. Permit only Android's read-only system
                # partitions as external absolute targets.
                if target == EXPECTED_PREFIX:
                    target_inside = "."
                elif target.startswith(EXPECTED_PREFIX + "/"):
                    target_inside = target[len(EXPECTED_PREFIX) + 1:]
                elif target.startswith(SYSTEM_LINK_ROOTS) and ".." not in target.split("/"):
                    target_inside = None
                else:
                    raise SystemExit(
                        f"absolute symlink points outside RustForge/system paths: {link} -> {target}"
                    )
                if target_inside is not None:
                    target = posixpath.relpath(
                        target_inside, start=posixpath.dirname(link) or "."
                    )
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(link), target))
            if not target.startswith("/") and not is_within_prefix(resolved):
                raise SystemExit(f"symlink escapes prefix: {link} -> {target}")
            if APP_ID not in target and ("com.termux" in target or "/data/data/" in target):
                raise SystemExit(f"symlink references another app: {link} -> {target}")
            symlinks[link] = target
            normalized_link_records.append(f"{target}←./{link}")

        # Store the normalized, relocatable symlink targets for Bootstrap.kt.
        old_info, _ = entries[LINKS_NAME]
        entries[LINKS_NAME] = (
            old_info,
            ("\n".join(normalized_link_records) + "\n").encode("utf-8"),
        )

        # Resolve every link chain against the archive's virtual tree.
        def resolve_link(link: str, seen: set[str] | None = None) -> str:
            seen = set() if seen is None else seen
            if link in seen:
                raise SystemExit(f"symlink cycle detected at {link}")
            seen.add(link)
            target = symlinks.get(link)
            if target is None:
                return link
            if target.startswith("/") and target.startswith(SYSTEM_LINK_ROOTS) and ".." not in target.split("/"):
                return target
            next_path = posixpath.normpath(posixpath.join(posixpath.dirname(link), target))
            if not is_within_prefix(next_path):
                raise SystemExit(f"symlink escapes prefix: {link} -> {target}")
            if next_path in symlinks:
                return resolve_link(next_path, seen)
            return next_path

        for link in symlinks:
            final_target = resolve_link(link)
            if final_target.startswith("/") and final_target.startswith(SYSTEM_LINK_ROOTS):
                continue
            if final_target not in all_paths:
                raise SystemExit(f"broken symlink: {link} -> {symlinks[link]} (resolves to {final_target})")

        def exists(path: str) -> bool:
            path = clean_name(path)
            if path in all_paths:
                return True
            if path in symlinks:
                return True
            return False

        required = [
            "bin/bash", "bin/apt", "bin/dpkg", "bin/rustc", "bin/cargo",
            "bin/git", "bin/cmake", "bin/ninja", "bin/clang", "bin/clang++",
            "etc/tls/cert.pem",
        ]
        missing = [path for path in required if not exists(path)]
        if missing:
            raise SystemExit("required bootstrap paths missing: " + ", ".join(missing))

        executable_paths = [
            "bin/bash", "bin/apt", "bin/dpkg", "bin/rustc", "bin/cargo",
            "bin/git", "bin/cmake", "bin/ninja", "bin/clang", "bin/clang++",
        ]
        for command in executable_paths:
            resolved = resolve_link(command)
            item = entries.get(resolved)
            if item is None:
                raise SystemExit(f"executable target missing from ZIP: {command} -> {resolved}")
            mode = stat.S_IMODE((item[0].external_attr >> 16) & 0xFFFF)
            if not mode & 0o111:
                raise SystemExit(f"required command is not executable in ZIP: {command} (mode={mode:04o})")
            payload = item[1]
            if payload.startswith(b"\x7fELF"):
                if len(payload) < 20 or int.from_bytes(payload[18:20], "little") != 183:
                    raise SystemExit(f"non-AArch64 ELF executable found for {command}")

        std_libs = [
            name for name in all_paths
            if name.startswith("lib/rustlib/aarch64-linux-android/lib/")
            and re.search(r"/libstd-[^/]+\.rlib$", name)
        ]
        if not std_libs:
            raise SystemExit("Rust standard library for aarch64-linux-android is missing")

        # Fail on old app-private paths. Internal package names may still contain
        # the word 'termux'; only foreign app-private prefixes are forbidden.
        for name, (_, data) in entries.items():
            if "com.termux" in name or any(old.encode() in data for old in OLD_PREFIXES) or b"com.termux" in data:
                raise SystemExit(f"foreign com.termux app identifier/path found in {name}")
        combined = b"\n".join(data for _, data in entries.values())
        if EXPECTED_PREFIX.encode() not in combined:
            raise SystemExit(
                "custom RustForge prefix not found in bootstrap payload; "
                "check TERMUX_APP__PACKAGE_NAME before building"
            )

        # Store Unix modes explicitly because Android's ZipInputStream does not
        # expose central-directory external attributes.
        mode_lines: list[str] = []
        for name, (info, _) in sorted(entries.items()):
            raw_mode = (info.external_attr >> 16) & 0xFFFF
            mode = stat.S_IMODE(raw_mode)
            if not mode:
                mode = 0o755 if info.is_dir() else 0o644
            mode_lines.append(f"{mode:04o}\t{name}")
        modes_data = ("\n".join(mode_lines) + "\n").encode()

        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
            for name, (info, data) in sorted(entries.items()):
                new_info = zipfile.ZipInfo(name + ("/" if info.is_dir() and not name.endswith("/") else ""))
                new_info.date_time = info.date_time
                new_info.compress_type = zipfile.ZIP_DEFLATED
                new_info.create_system = info.create_system or 3
                new_info.external_attr = info.external_attr
                new_info.flag_bits = info.flag_bits
                zout.writestr(new_info, data)
            modes_info = zipfile.ZipInfo(MODES_NAME)
            modes_info.create_system = 3
            modes_info.external_attr = (stat.S_IFREG | 0o600) << 16
            zout.writestr(modes_info, modes_data)

    with zipfile.ZipFile(args.output) as final_zip:
        bad = final_zip.testzip()
        if bad:
            raise SystemExit(f"final ZIP CRC validation failed at {bad}")
    print(f"Validated {args.output}")
    print(f"Archive entries: {len(entries)}; symlinks: {len(symlinks)}; Rust std files: {len(std_libs)}")
    print(f"Custom prefix: {EXPECTED_PREFIX}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"BOOTSTRAP_VALIDATION_ERROR: {exc}", file=sys.stderr)
        raise
