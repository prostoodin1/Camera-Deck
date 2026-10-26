# Third-party components

Camera Deck source is distributed under GPL-3.0-only. The public Windows executable includes PyQt6 and Qt. Corresponding Camera Deck source is attached to each release; dependency versions and build instructions are in README.md and desktop/requirements.txt.

- PyQt6 6.9.1: GPLv3 or a commercial license, Riverbank Computing. This build uses the GPLv3 distribution. https://www.riverbankcomputing.com/software/pyqt/
- Qt libraries supplied by PyQt6-Qt6: Qt's applicable LGPL/GPL and third-party notices accompany the upstream distribution. https://www.qt.io/licensing/open-source-lgpl-obligations
- cryptography 45.0.4: Apache-2.0 OR BSD-3-Clause. https://github.com/pyca/cryptography
- NanoHTTPD 2.3.1: BSD-3-Clause. https://github.com/NanoHttpd/nanohttpd
- CPython 3.13: Python Software Foundation License. https://docs.python.org/3/license.html
- PyInstaller bootloader: GPL with its distribution exception. https://pyinstaller.org/en/stable/license.html

- PyAV 19.0.1: BSD-3-Clause; packaged FFmpeg components have their own LGPL/GPL notices and corresponding source at https://github.com/PyAV-Org/PyAV and https://ffmpeg.org/download.html. The PyAV wheel build configuration/source is maintained at https://github.com/PyAV-Org/pyav-wheels. See bundled third_party_licenses/av-19.0.1.
- Google ML Kit face-detection 16.1.7 (bundled model), Google Play services / AndroidX transitive dependencies: https://developers.google.com/ml-kit/terms and their Maven POM license notices. Faces are processed locally. This SDK is an external dependency, not Camera Deck source.

The Android package uses Android system APIs. Development-only JUnit and JSON-java dependencies are not included in release APKs. No production signing private keys, Android keystores, user recordings or GitHub credentials are included in this repository.

## Android Platform Tools

Windows distributions include ADB and its Windows USB support DLLs from Android Platform Tools. The accompanying upstream license notices are preserved in `platform-tools/NOTICE.txt` inside the distribution. ADB source: https://android.googlesource.com/platform/packages/modules/adb/ .
