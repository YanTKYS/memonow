#!/usr/bin/env python3
"""Decode a user-supplied keystore in a private runner directory (no key generation)."""
import base64
import os
from pathlib import Path
import sys

required = ['MEMONOW_RELEASE_KEYSTORE_BASE64', 'MEMONOW_RELEASE_STORE_PASSWORD',
            'MEMONOW_RELEASE_KEY_ALIAS', 'MEMONOW_RELEASE_KEY_PASSWORD', 'MEMONOW_RELEASE_CERT_SHA256']
missing = [name for name in required if not os.environ.get(name, '').strip()]
if missing:
    sys.exit('Missing required release signing configuration: ' + ', '.join(missing))
try:
    content = base64.b64decode(os.environ['MEMONOW_RELEASE_KEYSTORE_BASE64'], validate=True)
    if not 0 < len(content) <= 1024 * 1024:
        raise ValueError()
    directory = Path(os.environ['RUNNER_TEMP']) / 'memonow-signing'
    directory.mkdir(mode=0o700, exist_ok=False)
    keystore = directory / 'release.jks'
    fd = os.open(keystore, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as output:
        output.write(content)
    with open(os.environ['GITHUB_ENV'], 'a') as output:
        output.write(f'MEMONOW_RELEASE_KEYSTORE_PATH={keystore}\n')
except (ValueError, OSError):
    sys.exit('Cannot prepare release keystore; check secure input and runner temporary storage.')
