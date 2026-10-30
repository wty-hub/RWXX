"""Check repository release naming/signing contracts without builds, secrets, or native apps.

This checks the deliberately narrow job/step/scalar layout used by ci.yml. It is
not a general YAML validator, a Windows launcher test, or a signing verification.
"""
import argparse
import json
import re
from pathlib import Path


def blocks(text, pattern):
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    return {match.group(1): text[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(text)]
            for index, match in enumerate(matches)}


def scalar(text, name):
    match = re.search(r'^\s*' + re.escape(name) + r':\s*([^\n]+)', text, re.MULTILINE)
    return match.group(1).strip().strip('"\'') if match else None


def check(properties, desktop, workflow, windows):
    errors = []
    def require(condition, message):
        if not condition:
            errors.append(message)

    name_match = re.search(r'^appName=([A-Za-z0-9][A-Za-z0-9._-]*)\s*$', properties, re.MULTILINE)
    if not name_match:
        return {'valid': False, 'staticOnly': True, 'errors': ['gradle.properties must declare a safe appName']}
    app_name = name_match.group(1)
    version_var = app_name.upper() + '_VERSION'
    jobs = blocks(workflow, r'^  ([A-Za-z0-9_-]+):\s*$')
    job_steps = {job: blocks(body, r'^      - name: ([^\n]+)$') for job, body in jobs.items()}

    def step(job, title):
        result = job_steps.get(job, {}).get(title)
        require(result is not None, f'missing CI step: {job}/{title}')
        return result or ''

    # Producer-side archive/image naming must actually use the property the workflow consumes.
    require('val appName: String by project' in desktop, 'desktop appName must come from the Gradle property')
    require(desktop.count('archiveBaseName = appName') >= 2, 'platform and universal JAR names must use appName')
    require('archiveClassifier = "all"' in desktop and 'archiveClassifier = targetPlatform.id' in desktop,
            'desktop JAR classifiers must match platform/all upload patterns')
    require('"--name", appName' in desktop, 'jpackage image name must use appName')
    require('"$appName-${project.version}-${targetPlatform.id}-desktop.zip"' in desktop,
            'desktop distribution filename must derive from appName/version/platform')
    require('outfile.set("$appName.exe")' in desktop, 'Windows launcher filename must derive from appName')
    require('"$packagedAppName/$appName.exe"' in desktop, 'installed Windows launcher must derive from appName')
    require(not re.search(r'-PappName(?:=|\s)', workflow), 'CI must not silently override the repository appName')

    desktop_upload = step('build-desktop', 'Upload desktop artifacts')
    require(scalar(desktop_upload, 'path') == f'desktop/build/distributions/{app_name}-*-${{{{ matrix.platform_id }}}}-desktop.zip',
            'desktop upload path must match the Gradle distribution filename')
    require(scalar(desktop_upload, 'if-no-files-found') == 'error', 'missing desktop artifacts must fail CI')
    universal_upload = step('build-universal-jar', 'Upload universal JAR')
    require(scalar(universal_upload, 'path') == f'desktop/build/libs/{app_name}-*-all.jar',
            'universal upload path must match the Gradle JAR filename')
    require(scalar(universal_upload, 'if-no-files-found') == 'error', 'missing universal JAR must fail CI')
    native_check = step('build-universal-jar', 'Check universal native library contents')
    require(f'python3 .github/scripts/check_desktop_natives.py desktop/build/libs/{app_name}-*-all.jar' in native_check,
            'universal native contents must be checked before artifact upload')
    for job, steps in job_steps.items():
        for title, body in steps.items():
            if 'uses: actions/upload-artifact@' in body:
                require((scalar(body, 'name') or '').startswith(app_name + '-'),
                        f'artifact name must use appName: {job}/{title}')
    download = step('publish-release', 'Download release artifacts')
    require(scalar(download, 'pattern') == app_name + '-*', 'release download pattern must match uploaded artifact names')

    windows_step = step('build-desktop', 'Verify Windows launcher uses bundled runtime')
    require(f'-ImagePath desktop/build/jpackage/image/windows-x64/{app_name}' in windows_step,
            'Windows runtime check must use the generated image directory')
    require(re.search(r'-AppName\s+' + re.escape(app_name) + r'(?:\s|$)', windows_step) is not None,
            'Windows runtime check must receive the repository appName')
    default_name = re.search(r'\[string\]\$AppName\s*=\s*[\'"]([^\'"]+)[\'"]', windows)
    require(default_name is not None and default_name.group(1) == app_name,
            'Windows runtime script AppName default must match Gradle')
    for expression in ('Join-Path $resolvedImage "${AppName}.exe"', 'Join-Path $testImage "${AppName}.exe"',
                       'Join-Path $testRoot $AppName', '${AppName}-launcher-collision-'):
        require(expression in windows, 'Windows runtime paths must derive from AppName')
    require(re.search(r'\bRWX\b|\brwx-', windows) is None, 'Windows runtime script retains an old product name')

    for job, build_step in (('build-desktop', 'Build platform JAR and jpackage image'),
                            ('build-universal-jar', 'Build universal JAR'), ('build-android', 'Build APK')):
        resolve = step(job, 'Resolve release version')
        require(f'echo "{version_var}=$version" >> "$GITHUB_ENV"' in resolve,
                f'{job} must export the product version variable')
        require(f'-Pversion="${version_var}"' in step(job, build_step),
                f'{job} must consume the same product version variable')
    require(re.search(r'\bRWX_VERSION\b|\bRWX-', workflow) is None, 'CI retains an old product name/version variable')
    android_stage = step('build-android', 'Stage Android artifacts')
    require(f'android-artifacts/{app_name}-${version_var}-$name' in android_stage,
            'staged Android APK names must use the product name/version')

    # Validate references/policy only; never fetch, echo, or include a value in an error message.
    android = jobs.get('build-android', '')
    for suffix in ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'):
        require(scalar(android, 'SIGNING_' + suffix) == '${{ secrets.ANDROID_' + suffix + ' }}',
                f'Android SIGNING_{suffix} must reference the configured GitHub secret, not a literal')
    validate = step('build-android', 'Validate Android signing configuration')
    for suffix in ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'):
        require(f'test -n "$SIGNING_{suffix}"' in validate, f'Android signing must reject an absent {suffix}')
    restore = step('build-android', 'Restore Android signing keystore')
    require(f'keystore="$RUNNER_TEMP/{app_name}-android-signing.p12"' in restore,
            'temporary signing keystore must use appName under RUNNER_TEMP')
    require('base64 --decode > "$keystore"' in restore, 'signing material must be decoded into the temporary keystore')
    cleanup = step('build-android', 'Remove Android signing keystore')
    require(scalar(cleanup, 'if') == 'always()' and 'rm -f "$KEYSTORE_FILE"' in cleanup,
            'temporary signing keystore must be removed even after a failed build')
    for line in workflow.splitlines():
        stripped = line.strip()
        require(not re.match(r'(?:set\s+-[^\s]*x|printenv(?:\s|$)|env\s*$)', stripped),
                'CI must not enable shell tracing or dump secret-bearing environment values')
        if re.match(r'(?:echo|printf|Write-Host|Write-Output)\b', stripped) and re.search(r'\$(?:\{)?(?:SIGNING_|KEYSTORE_PASSWORD|KEY_PASSWORD|KEY_ALIAS)', stripped):
            allowed = '>> "$GITHUB_ENV"' in stripped or ('base64 --decode > "$keystore"' in stripped)
            require(allowed, 'CI must not print Android signing values to logs')

    return {'valid': not errors, 'staticOnly': True, 'appName': app_name, 'errors': errors,
            'windowsRuntimeVerified': False, 'signedReleaseVerified': False, 'crossPlatformBuildVerified': False}


def check_repository(root):
    root = Path(root)
    sources = [(root / path).read_text(encoding='utf-8') for path in (
        'gradle.properties', 'desktop/build.gradle.kts', '.github/workflows/ci.yml',
        '.github/scripts/test-windows-bundled-runtime.ps1')]
    return check(*sources)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    result = check_repository(args.root)
    print(json.dumps(result, indent=2))
    return 0 if result['valid'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
