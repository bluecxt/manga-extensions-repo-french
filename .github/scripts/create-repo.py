import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.extension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_REGEX = re.compile(r"^application-icon-[0-9]+:'([^']+)'", re.MULTILINE)
ICON_FALLBACK_REGEX = re.compile(r"^icon='([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")

# Locate aapt from ANDROID_HOME if present, otherwise use system aapt
aapt_cmd = "aapt"
if "ANDROID_HOME" in os.environ:
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    if build_tools.exists():
        tool_dirs = sorted(build_tools.iterdir())
        if tool_dirs:
            aapt_cmd = str(tool_dirs[-1] / "aapt")

REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"
REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

# Load existing index.min.json if present
existing_index_by_pkg = {}
existing_index_file = REPO_DIR / "index.min.json"
if existing_index_file.exists():
    try:
        with existing_index_file.open(encoding="utf-8") as f:
            for item in json.load(f):
                existing_index_by_pkg[item["pkg"]] = item
    except Exception as e:
        print(f"Warning: Failed to parse existing index.min.json: {e}")

# Load all downloaded keiyoushi-source-info.json files
source_info_map = {}
for info_file in Path.home().joinpath("apk-artifacts").glob("**/keiyoushi-source-info.json"):
    try:
        with info_file.open(encoding="utf-8") as f:
            info = json.load(f)
            source_info_map[info["packageName"]] = info
    except Exception as e:
        print(f"Warning: Could not read {info_file}: {e}")

index_min_data = []

for apk in sorted(REPO_APK_DIR.glob("*.apk")):
    badging = subprocess.check_output(
        [
            aapt_cmd,
            "dump",
            "--include-meta-data",
            "badging",
            str(apk),
        ]
    ).decode("utf-8", errors="ignore")

    package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
    package_name = PACKAGE_NAME_REGEX.search(package_info)[1]

    # Extract icon
    icon_match = (
        APPLICATION_ICON_320_REGEX.search(badging)
        or APPLICATION_ICON_REGEX.search(badging)
        or ICON_FALLBACK_REGEX.search(badging)
    )
    if icon_match:
        icon_path = icon_match[1]
        try:
            with ZipFile(apk) as z, z.open(icon_path) as i, (REPO_ICON_DIR / f"{package_name}.png").open("wb") as out_icon:
                out_icon.write(i.read())
        except Exception as e:
            print(f"Warning: Failed to extract icon {icon_path} from {apk.name}: {e}")

    language_match = LANGUAGE_REGEX.search(apk.name)
    language = language_match[1] if language_match else "all"

    # Try getting sources from source_info_map first, then fallback to existing index
    sources = []
    if package_name in source_info_map:
        for s in source_info_map[package_name].get("sources", []):
            sources.append({
                "name": s["name"],
                "lang": s["lang"],
                "id": s["id"],
                "baseUrl": s["baseUrl"],
                "versionId": s.get("versionId", 1),
            })
    elif package_name in existing_index_by_pkg:
        sources = existing_index_by_pkg[package_name].get("sources", [])
    else:
        label_match = APPLICATION_LABEL_REGEX.search(badging)
        sources = [{
            "name": label_match[1] if label_match else package_name,
            "lang": language,
            "id": 0,
            "baseUrl": "",
            "versionId": 1,
        }]

    if len(sources) == 1:
        source_language = sources[0].get("lang")
        if (
            source_language
            and source_language != language
            and source_language not in {"all", "other"}
            and language not in {"all", "other"}
        ):
            language = source_language

    label_match = APPLICATION_LABEL_REGEX.search(badging)
    name = label_match[1] if label_match else apk.stem
    version_code_match = VERSION_CODE_REGEX.search(package_info)
    code = int(version_code_match[1]) if version_code_match else 0
    version_name_match = VERSION_NAME_REGEX.search(package_info)
    version = version_name_match[1] if version_name_match else "1.0"
    nsfw_match = IS_NSFW_REGEX.search(badging)
    nsfw = int(nsfw_match[1]) if nsfw_match else 0

    entry = {
        "name": name,
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": code,
        "version": version,
        "nsfw": nsfw,
        "sources": sources,
    }
    index_min_data.append(entry)

index_min_data.sort(key=lambda x: x["pkg"])

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as index_file:
    json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))

print(f"Successfully generated index.min.json with {len(index_min_data)} extensions.")
