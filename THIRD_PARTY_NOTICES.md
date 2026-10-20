# Third-party components

Camera Deck source is distributed under GPL-3.0-only. The public Windows executable includes PyQt6 and Qt. Corresponding Camera Deck source is attached to each release; dependency versions and build instructions are in README.md and desktop/requirements.txt.

- PyQt6 6.9.1: GPLv3 or a commercial license, Riverbank Computing. This build uses the GPLv3 distribution. https://www.riverbankcomputing.com/software/pyqt/
- Qt libraries supplied by PyQt6-Qt6: Qt's applicable LGPL/GPL and third-party notices accompany the upstream distribution. https://www.qt.io/licensing/open-source-lgpl-obligations
- cryptography 45.0.4: Apache-2.0 OR BSD-3-Clause. https://github.com/pyca/cryptography
- NanoHTTPD 2.3.1: BSD-3-Clause. https://github.com/NanoHttpd/nanohttpd
- CPython 3.13: Python Software Foundation License. https://docs.python.org/3/license.html
- PyInstaller bootloader: GPL with its distribution exception. https://pyinstaller.org/en/stable/license.html

The Android package uses Android system APIs. Development-only JUnit and JSON-java dependencies are not included in release APKs. No production signing private keys, Android keystores, user recordings or GitHub credentials are included in this repository.
