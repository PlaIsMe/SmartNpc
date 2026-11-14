"""Verify selected model/language GitHub assets against local export receipts.

Full profiles require a completed batch; --models permits an explicitly selected
exported subset. Atomically update the catalog only after every selection matches. Never
uploads, deletes release assets, or changes installed game-instance catalogs.
"""
import argparse
import hashlib
import json
import os
import re
import urllib.request
from pathlib import Path
from urllib.parse import quote

from model_paths import MODEL_IDS, MODEL_ROOT, REPOSITORY_ROOT, model_metadata
from release_workflow import REPOSITORY, artifact, write_json
from retrain import PROFILES, RECEIPTS, profile_language, identity as job_identity, final_filename

CATALOG = REPOSITORY_ROOT / "common/src/main/resources/data/smart_npc/ai/downloads.json"
DEFAULT_RELEASE_VERSION = "1.0"


def release_tag(language, version, profile="standard"):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", version):
        raise ValueError("Release version must be numeric, for example 1.0 or 1.1")
    if profile not in {"standard", "unfiltered"}:
        raise ValueError("Profile must be standard or unfiltered")
    # Standard and Unfiltered assets share their locale's release; filenames distinguish them.
    return f"llm-{language.replace('_', '-')}-v{version}"


def get_release(tag):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "SmartNpc-release-sync/1",
               "X-GitHub-Api-Version": "2022-11-28", "Cache-Control": "no-cache"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(f"https://api.github.com/repos/{REPOSITORY}/releases/tags/{quote(tag, safe='')}", headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def remote_digest(asset):
    digest = asset.get("digest") or ""
    if re.fullmatch(r"sha256:[a-fA-F0-9]{64}", digest):
        return digest.split(":", 1)[1].lower()
    # Older GitHub assets may have no digest. Stream and hash without saving a duplicate.
    print(f"GitHub has no SHA-256 for {asset['name']}; downloading to verify...", flush=True)
    request = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": "SmartNpc-release-sync/1"})
    digest = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(request, timeout=120) as response:
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            if count > asset["size"]:
                raise ValueError("Remote asset exceeded its declared size")
            digest.update(chunk)
    if count != asset["size"]:
        raise ValueError("Remote asset download was incomplete")
    return digest.hexdigest()


def synchronize(check=False, release_version=DEFAULT_RELEASE_VERSION, model_dir=None, profiles=None, models=None):
    profiles = list(PROFILES) if profiles is None else profiles
    models = list(MODEL_IDS) if models is None else models
    if not profiles or len(set(profiles)) != len(profiles) or any(p not in PROFILES for p in profiles):
        raise ValueError("Choose distinct supported datasets")
    if not models or len(set(models)) != len(models) or any(m not in MODEL_IDS for m in models):
        raise ValueError("Choose distinct current model tiers")
    selected_jobs = [(model, profile) for profile in profiles for model in models]
    receipts = {}
    for profile in profiles:
        path = RECEIPTS / f"{profile}.json"
        if not path.is_file():
            raise ValueError(f"Run retrain.py --{profile} first; missing {path}")
        batch = json.loads(path.read_text(encoding="utf-8"))
        rows = batch.get("models", [])
        ids = {r["id"] for r in rows}
        expected = {job_identity(m, profile) for m in models}
        allowed = {job_identity(m, profile) for m in MODEL_IDS}
        if batch.get("schema") != 1 or batch.get("profile") != profile or len(ids) != len(rows) or not expected <= ids or not ids <= allowed:
            raise ValueError(f"Incomplete or mismatched {profile} receipt; finish the selected models first")
        receipts.update({r["id"]: r for r in rows})
    expected_ids = {job_identity(m, p) for m, p in selected_jobs}
    tags = {profile_language(p): release_tag(profile_language(p), release_version) for p in profiles}
    releases = {}
    original = CATALOG.read_bytes()
    catalog = json.loads(original)
    entries = {row["id"]: row for row in catalog["models"]}
    if catalog.get("schema") != 1 or len(entries) != len(catalog["models"]):
        raise ValueError("Catalog must contain unique model IDs")
    changed = False
    for model, selected_profile in selected_jobs:
        language = profile_language(selected_profile)
        profile = "unfiltered" if selected_profile == "en_us_unfiltered" else "standard"
        identity = job_identity(model, selected_profile)
        receipt = receipts[identity]
        if receipt.get("family") != model or receipt.get("language") != language or receipt.get("profile") != profile:
            raise ValueError(f"Batch family/language/profile mismatch: {identity}")
        name = final_filename(model, selected_profile)
        path = Path(model_dir) / name if model_dir is not None else MODEL_ROOT / name
        if model_dir is None and not path.is_file():
            path = MODEL_ROOT / "export" / name
        local = artifact(path)
        if any(receipts[identity].get(key) != value for key, value in local.items()):
            raise ValueError(f"Local file differs from completed batch: {name}")
        tag = tags[language]
        if tag not in releases:
            releases[tag] = get_release(tag)
        release = releases[tag]
        if release.get("draft") or release.get("tag_name") != tag:
            raise ValueError(f"Expected a published release with tag {tag}")
        matches = [asset for asset in release.get("assets", []) if asset.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Upload {name} to release {tag} first (exact versionless filename required).")
        asset = matches[0]
        url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}"
        if asset.get("state") != "uploaded" or asset.get("browser_download_url") != url or asset.get("size") != local["downloadBytes"]:
            raise ValueError(f"Uploaded asset metadata does not match the local export: {identity}")
        if remote_digest(asset) != local["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {identity}. Replace the release asset with the new local GGUF.")
        update = {**local, "url": url}
        entry = entries.get(identity)
        if entry is None:
            # New locales become selectable only after a real uploaded asset passes every check.
            metadata = model_metadata(model)
            label = "English (Unfiltered)" if profile == "unfiltered" else {"en_us": "English", "zh_cn": "Simplified Chinese", "vi_vn": "Vietnamese"}[language]
            entry = {"id": identity, "name": metadata["name"], "family": model, "language": language,
                     "license": metadata["license"], "engineRequirement": metadata["engine_requirement"],
                     "ramEstimate": model_metadata(model)["ram"], "description": f"Smart NPC fine-tuned model; {label}"}
            entries[identity] = entry
            catalog["models"].append(entry)
            changed = True
        if entry.get("family") != model or entry.get("language") != language:
            raise ValueError(f"Catalog family/language mismatch: {identity}")
        changed |= any(entry.get(key) != value for key, value in update.items())
        if entry.get("sha256") != local["sha256"]:
            entry["ramEstimate"] = model_metadata(model)["ram"]
        entry.update(update)
        print(f"Verified {identity}: {local['sha256']}", flush=True)
    if CATALOG.read_bytes() != original:
        raise ValueError("Catalog changed while verifying; rerun instead of overwriting another edit.")
    if changed:
        catalog["catalogRevision"] = max(5, catalog.get("catalogRevision", 0)) + 1
        if not check:
            write_json(CATALOG, catalog)
        print(f"{'Would update' if check else 'Updated'} {CATALOG} (revision {catalog['catalogRevision']})")
    else:
        print(f"All {len(expected_ids)} releases match. Catalog already up to date.")
    if not check:
        print("Rebuild the mod to ship this catalog. No GitHub assets were changed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify and preview changes without writing the catalog")
    parser.add_argument("--release-version", default=DEFAULT_RELEASE_VERSION, help="Language release tag version: llm-en-us-vVERSION, llm-zh-cn-vVERSION, llm-vi-vn-vVERSION (default: 1.0)")
    parser.add_argument("--model-dir", type=Path, help="Directory containing selected GGUFs; default checks llm-model then llm-model/export")
    parser.add_argument("--all", action="store_true", help="Sync all four dataset profiles (default)")
    for profile in PROFILES:
        parser.add_argument("--" + profile, action="store_true")
    parser.add_argument("--models", nargs="+", choices=MODEL_IDS, help="Sync only verified exports for these tiers")
    args = parser.parse_args()
    profiles = [p for p in PROFILES if args.all or getattr(args, p)] or list(PROFILES)
    try:
        synchronize(args.check, args.release_version, args.model_dir, profiles, args.models)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"Catalog unchanged: {error}\n")


if __name__ == "__main__":
    main()
