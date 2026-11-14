"""Prepare the existing exporter's tool folder using an installed llama.cpp runtime; no build."""
import argparse
import hashlib
import json
import os
import platform
import shlex
import shutil
import tempfile
from pathlib import Path

from export_tools import cache_converter, converter_python, find_quantizer, portable_path, runtime_tag
from model_paths import MODEL_ROOT, REPOSITORY_ROOT


WSL_QUANTIZER = '''#!/usr/bin/env python3
"""Pass WSL file arguments to the copied Windows quantizer."""
import subprocess
import sys
from pathlib import Path

if len(sys.argv) < 4:
    raise SystemExit("Expected input.gguf output.gguf quantization_type")
windows_paths = [subprocess.check_output(["wslpath", "-w", str(Path(value).resolve())], text=True).strip() for value in sys.argv[1:3]]
binary = Path(__file__).resolve().with_name("llama-quantize.exe")
raise SystemExit(subprocess.run([str(binary), *windows_paths, *sys.argv[3:]]).returncode)
'''


def file_hash(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def is_wsl():
    return os.name != 'nt' and 'microsoft' in platform.release().lower()


def prepare_layout(runtime, converter, target, tag, wsl):
    receipt_name = 'smartnpc-export-tools.json'
    if target.exists():
        receipt = target / receipt_name
        if not receipt.is_file():
            raise ValueError(f'Tool folder already exists without a preparation receipt: {target}; choose --output with a new folder.')
        metadata = json.loads(receipt.read_text(encoding='utf-8'))
        if metadata.get('tag') != tag or metadata.get('wsl') != wsl or not metadata.get('files'):
            raise ValueError('Prepared tool folder version/platform mismatch; choose another --output folder.')
        for relative, digest in metadata['files'].items():
            path = target / relative
            if not path.resolve().is_relative_to(target.resolve()) or not path.is_file() or file_hash(path) != digest:
                raise ValueError(f'Prepared export tool changed: {path}')
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='export-tools-', dir=target.parent) as temporary:
        staging = Path(temporary) / 'tools'
        shutil.copytree(converter.parent, staging)
        binary_directory = staging / 'build/bin'
        binary_directory.mkdir(parents=True)
        total = 0
        entries = list(runtime.rglob('*'))
        if len(entries) > 1024:
            raise ValueError('Runtime folder has too many files; supply the installed runtime folder directly.')
        for source in entries:
            if source.is_symlink() or not source.resolve().is_relative_to(runtime.resolve()):
                raise ValueError('Runtime folder contains a link outside its directory')
            relative = source.relative_to(runtime)
            destination = binary_directory / relative
            if source.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
                continue
            if not source.is_file():
                raise ValueError(f'Runtime entry is not a regular file: {source}')
            total += source.stat().st_size
            if total > 512 * 1024 * 1024:
                raise ValueError('Runtime copy exceeds 512 MiB')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        if not (binary_directory / 'llama-quantize.exe').is_file():
            raise ValueError('The installed runtime must contain llama-quantize.exe in its root.')
        if wsl:
            wrapper = binary_directory / 'llama-quantize'
            wrapper.write_text(WSL_QUANTIZER, encoding='utf-8', newline='\n')
            wrapper.chmod(0o755)
        files = {path.relative_to(staging).as_posix(): file_hash(path) for path in staging.rglob('*') if path.is_file()}
        (staging / receipt_name).write_text(json.dumps({'tag': tag, 'wsl': wsl,
                'runtime_source': str(runtime), 'files': files}, indent=2), encoding='utf-8')
        staging.rename(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=portable_path, help='Installed runtime folder or executable; defaults to the executable in --config')
    parser.add_argument('--config', type=portable_path, default=REPOSITORY_ROOT / 'fabric/run/config/smart_npc/ai.json')
    parser.add_argument('--output', type=portable_path, help='Default: llm-model/tools/llama.cpp-<build>[-wsl]')
    parser.add_argument('--skip-deps', action='store_true', help='Only prepare files; leave Python dependencies to your existing export environment')
    args = parser.parse_args()
    runtime = args.runtime
    if runtime is None:
        config = json.loads(args.config.read_text(encoding='utf-8'))
        if not config.get('executable'):
            raise ValueError('No installed runtime configured; supply --runtime /path/to/runtime')
        runtime = portable_path(config['executable'])
    runtime, quantizer = find_quantizer(runtime)
    if quantizer.parent != runtime or quantizer.name != 'llama-quantize.exe':
        raise ValueError('Supply the installed Windows runtime folder, rather than a source checkout.')
    wsl = is_wsl()
    if os.name != 'nt' and not wsl:
        raise ValueError('Reusing this Windows runtime requires Windows or WSL interoperability.')
    tag = runtime_tag(runtime)
    converter = cache_converter(tag)
    target = args.output or MODEL_ROOT / 'tools' / (f'llama.cpp-{tag}' + ('-wsl' if wsl else '-windows'))
    print(f'Copying installed {tag} runtime into export tools; no cloning or compilation...', flush=True)
    prepare_layout(runtime, converter, target, tag, wsl)
    if not args.skip_deps:
        python = converter_python(converter, tag)
        if os.name == 'nt':
            print('Activate export environment: & "' + str(python.parent / 'Activate.ps1') + '"', flush=True)
        else:
            print('Activate export environment: source ' + shlex.quote(str(python.parent / 'activate')), flush=True)
    else:
        print('Python dependencies were not installed (--skip-deps).', flush=True)
    print(f'Ready. Pass this tool folder to the unchanged export_gguf.py: {target}', flush=True)


if __name__ == '__main__':
    main()
