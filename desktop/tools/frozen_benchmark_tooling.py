"""Freeze measurement scripts and original-agent sources, separately from the game runtime."""
import hashlib
import json
from pathlib import Path
import shutil


def fingerprint(directory):
    files = sorted(p for p in directory.rglob('*') if p.is_file() and p.suffix in ('.py', '.java'))
    manifest = {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    digest = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return digest, manifest


def freeze_tooling(project, output):
    source = project / 'desktop/tools'
    destination = output / 'tooling'
    destination.mkdir(exist_ok=False)
    for path in source.rglob('*'):
        if path.is_file() and path.suffix in ('.py', '.java'):
            target = destination / path.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    digest, manifest = fingerprint(destination)
    (output / 'tooling-manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2), encoding='utf-8')
    return destination, digest


def frozen_environment(project, directory, digest, environment):
    return {**environment, 'RWX_BENCHMARK_PROJECT_ROOT': str(project),
            'RWX_BENCHMARK_TOOL_ROOT': str(directory), 'RWX_BENCHMARK_TOOLING_SHA256': digest}
