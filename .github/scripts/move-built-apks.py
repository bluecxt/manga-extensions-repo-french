import json
import os
import re
import shutil
from pathlib import Path

REPO_APK_DIR = Path("repo/apk")

# Ensure the directory exists
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

# Path where GitHub Actions downloads the artifacts
ARTIFACTS_DIR = Path.home().joinpath("apk-artifacts")

def get_pkg_name(filename):
    # tachiyomi-fr.japscan-v1.4.70.apk -> fr.japscan
    m = re.match(r"tachiyomi-(.*?)-v.*\.apk", filename)
    return m.group(1) if m else None

def get_excluded_patterns():
    exclude_file = Path("exclude_build.json")
    if exclude_file.is_file():
        try:
            with exclude_file.open("r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            print(f"Warning: Failed to load exclude_build.json: {e}")
    return []

def is_excluded(pkg_name, exclude_patterns):
    for pattern in exclude_patterns:
        if pattern.endswith(".*"):
            prefix = pattern[:-2]
            if pkg_name.startswith(prefix + "."):
                return True
        elif pkg_name == pattern:
            return True
    return False

# 1. Cleanup: Remove APKs for extensions that no longer exist in src/ or are excluded in exclude_build.json
if REPO_APK_DIR.exists():
    exclude_patterns = get_excluded_patterns()
    active_extensions = set()
    src_dir = Path("src")
    if src_dir.exists():
        for lang_dir in src_dir.iterdir():
            if lang_dir.is_dir():
                for ext_dir in lang_dir.iterdir():
                    if ext_dir.is_dir() and not (ext_dir / ".ignore").exists():
                        pkg = f"{lang_dir.name}.{ext_dir.name}"
                        if not is_excluded(pkg, exclude_patterns):
                            active_extensions.add(pkg)

    # Check all APKs in the repo
    for repo_apk in REPO_APK_DIR.glob("*.apk"):
        pkg_name = get_pkg_name(repo_apk.name)
        if pkg_name and pkg_name not in active_extensions:
            print(f"Deleting orphaned APK (extension removed from src or excluded): {repo_apk.name}")
            repo_apk.unlink()

# 2. Move new APKs
if ARTIFACTS_DIR.exists():
    for apk in ARTIFACTS_DIR.glob("**/*.apk"):
        apk_name = apk.name
        if apk_name.endswith("-release.apk"):
            apk_name = apk_name.replace("-release.apk", ".apk")
        elif apk_name.endswith("-debug.apk"):
            apk_name = apk_name.replace("-debug.apk", ".apk")

        pkg_name = get_pkg_name(apk_name)
        if pkg_name:
            # Remove ANY existing APK for this package to avoid duplicate versions
            for existing_apk in REPO_APK_DIR.glob(f"tachiyomi-{pkg_name}-v*.apk"):
                print(f"Removing old version: {existing_apk.name}")
                existing_apk.unlink()

        dest_path = REPO_APK_DIR.joinpath(apk_name)
        shutil.move(apk, dest_path)
        print(f"Moved {apk.name} to {dest_path}")
else:
    print("No artifacts directory found.")

