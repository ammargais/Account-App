#!/usr/bin/env python3
"""Validate the owner-signed APK against its recorded source digest and signature."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = ('app/src', 'app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat')

def source_digest():
    files = []
    for name in SOURCE_ROOTS:
        p = ROOT / name
        files.extend(p.rglob('*') if p.is_dir() else [p])
    result = hashlib.sha256()
    for p in sorted((p for p in files if p.is_file()), key=lambda p: p.relative_to(ROOT).as_posix()):
        result.update(p.relative_to(ROOT).as_posix().encode() + b'\0')
        result.update(hashlib.sha256(p.read_bytes()).digest())
    return result.hexdigest()

def verify(apk):
    manifest = json.loads((ROOT / 'distribution/release.json').read_text())
    assert source_digest() == manifest['source_sha256'], 'Source has changed since the signed APK was built'
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == manifest['apk_sha256'], 'APK hash mismatch'
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
    signing = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signing)
    assert certs == [manifest['signer_sha256']], 'APK signer mismatch'
    badging = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    assert f"name='{manifest['application_id']}'" in badging.splitlines()[0]
    assert f"versionCode='{manifest['version_code']}'" in badging.splitlines()[0]
    assert f"versionName='{manifest['version_name']}'" in badging.splitlines()[0]
    assert 'application-debuggable' not in badging, 'Distribution APK must not be debuggable'
    print('Verified APK bytes, signer, package, version and recorded source digest.')

if __name__ == '__main__':
    if sys.argv[1:] == ['--source-digest']:
        print(source_digest())
    else:
        verify(Path(sys.argv[1]))
