"""Reuse an installed quantizer; cache matching Python conversion tools separately."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath

from model_paths import MODEL_ROOT


def portable_path(value):
    if os.name != 'nt' and re.match(r'^[A-Za-z]:[\\/]', str(value)):
        value = subprocess.check_output(['wslpath', '-u', str(value)], text=True).strip()
    return Path(value).expanduser().resolve()


def find_quantizer(root):
    if root.is_file() and root.name in {'llama-server.exe', 'llama-server', 'llama-quantize.exe', 'llama-quantize'}:
        root = root.parent
    candidates = [root / directory / name for directory in ('', 'build/bin', 'build/bin/Release', 'bin')
                  for name in ('llama-quantize', 'llama-quantize.exe')]
    quantizer = next((path for path in candidates if path.is_file()), None)
    if quantizer is None:
        raise ValueError(f'No llama-quantize executable found in {root}')
    return root, quantizer.resolve()


def runtime_tag(root):
    receipt = root / 'smartnpc-runtime-receipt.json'
    identity = root.name
    if receipt.is_file():
        identity = json.loads(receipt.read_text(encoding='utf-8')).get('runtime_id', identity)
    match = re.fullmatch(r'llama-(b[0-9]{1,12})-cpu-windows-x64(?:-[a-fA-F0-9]{12})?', identity)
    if not match:
        raise ValueError('Cannot identify this runtime version. Supply --converter /path/to/convert_hf_to_gguf.py.')
    return match.group(1)


def converter_member(name):
    parts = PurePosixPath(name).parts
    if len(parts) < 2 or any(part in {'.', '..'} or '\\' in part or ':' in part for part in parts):
        return None
    relative = PurePosixPath(*parts[1:])
    if relative.is_absolute():
        return None
    value = relative.as_posix()
    if value in {'convert_hf_to_gguf.py', 'LICENSE'}:
        return relative
    if value.startswith(('conversion/', 'gguf-py/gguf/')) and value.endswith('.py'):
        return relative
    if value in {'requirements/requirements-convert_hf_to_gguf.txt', 'requirements/requirements-convert_legacy_llama.txt'}:
        return relative
    return None


def cache_converter(tag):
    tools = MODEL_ROOT / 'tools'
    tools.mkdir(parents=True, exist_ok=True)
    target = tools / f'llama-converter-{tag}'
    receipt_path = target / 'smartnpc-converter-receipt.json'
    if target.exists():
        if not receipt_path.is_file():
            raise ValueError(f'Incomplete converter cache: {target}; move it aside before retrying.')
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        if receipt.get('tag') != tag or not receipt.get('files'):
            raise ValueError('Converter cache version/receipt mismatch')
        for relative, digest in receipt['files'].items():
            if converter_member('source/' + relative) is None:
                raise ValueError('Invalid converter cache receipt path')
            path = target / relative
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise ValueError(f'Converter cache changed: {path}')
        return target / 'convert_hf_to_gguf.py'

    print(f'Downloading Python converter tools matching {tag}; reusing the installed quantizer...', flush=True)
    url = f'https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/{tag}'
    with tempfile.TemporaryDirectory(prefix='converter-', dir=tools) as temporary:
        staging = Path(temporary) / 'scripts'
        staging.mkdir()
        archive = Path(temporary) / 'source.tar.gz'
        digest = hashlib.sha256()
        size = 0
        request = urllib.request.Request(url, headers={'User-Agent': 'SmartNpc-model-export/1'})
        with urllib.request.urlopen(request, timeout=120) as response, archive.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > 128 * 1024 * 1024:
                    raise ValueError('Converter source archive exceeds 128 MiB')
                digest.update(chunk)
                output.write(chunk)
        files = {}
        expanded = 0
        with tarfile.open(archive, 'r:gz') as source:
            for member in source:
                relative = converter_member(member.name)
                if relative is None or not member.isfile():
                    continue
                expanded += member.size
                if len(files) >= 1024 or expanded > 64 * 1024 * 1024:
                    raise ValueError('Converter scripts exceed extraction limits')
                destination = staging.joinpath(*relative.parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as input_file, destination.open('xb') as output:
                    payload = input_file.read()
                    output.write(payload)
                files[relative.as_posix()] = hashlib.sha256(payload).hexdigest()
        required = ['convert_hf_to_gguf.py', 'conversion/__init__.py', 'gguf-py/gguf/__init__.py',
                    'requirements/requirements-convert_hf_to_gguf.txt', 'requirements/requirements-convert_legacy_llama.txt']
        if not all(name in files for name in required):
            raise ValueError('Upstream converter layout changed; required Python files are missing')
        (staging / receipt_path.name).write_text(json.dumps({'tag': tag, 'url': url,
                'archive_sha256': digest.hexdigest(), 'files': files}, indent=2), encoding='utf-8')
        staging.rename(target)
    return target / 'convert_hf_to_gguf.py'


def converter_python(converter, tag):
    # Keep upstream's conversion dependencies separate from CUDA/Unsloth training packages.
    environment = Path.home() / '.smartnpc' / 'export-envs' / tag
    executable = environment / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    requirements = converter.parent / 'requirements/requirements-convert_hf_to_gguf.txt'
    legacy = requirements.parent / 'requirements-convert_legacy_llama.txt'
    fingerprint = hashlib.sha256(requirements.read_bytes() + legacy.read_bytes()).hexdigest()
    receipt = environment / 'smartnpc-export-environment.json'
    if executable.is_file() and receipt.is_file() and json.loads(receipt.read_text()).get('requirements_sha256') == fingerprint:
        return executable
    uv = shutil.which('uv')
    if uv is None:
        candidate = Path.home() / '.local/bin' / ('uv.exe' if os.name == 'nt' else 'uv')
        uv = str(candidate) if candidate.is_file() else None
    print(f'Preparing isolated CPU export environment: {environment}', flush=True)
    environment.parent.mkdir(parents=True, exist_ok=True)
    if not executable.is_file():
        command = [uv, 'venv', '--python', '3.12', str(environment)] if uv else [sys.executable, '-m', 'venv', str(environment)]
        subprocess.run(command, check=True)
    if uv:
        subprocess.run([uv, 'pip', 'install', '--python', str(executable), '--torch-backend=cpu', '-r', str(requirements)], check=True)
    else:
        subprocess.run([str(executable), '-m', 'pip', 'install', '--index-url', 'https://download.pytorch.org/whl/cpu', 'torch==2.11.0'], check=True)
        subprocess.run([str(executable), '-m', 'pip', 'install', '-r', str(requirements)], check=True)
    receipt.write_text(json.dumps({'requirements_sha256': fingerprint, 'converter_tag': tag}), encoding='utf-8')
    return executable


