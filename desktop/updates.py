"""Signed public GitHub prereleases. Installed applications do not need a token."""
from __future__ import annotations
import base64
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import urllib.request
from urllib.parse import urlparse
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

VERSION = "0.11.1"
VERSION_CODE = 17
RELEASE_NAME = "Client"
PRODUCT = "camera-deck-client"
API = "https://api.github.com/repos/prostoodin1/Camera-Deck"
ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
DATA = Path(os.environ.get("CAMERA_DECK_DATA", str(Path(os.environ.get("LOCALAPPDATA", Path.home())) / "CameraDeck")))
_download_commit=threading.Lock()


class _Blob(ctypes.Structure):
    _fields_ = [("size", ctypes.c_ulong), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def _protect(data: bytes, decrypt: bool = False) -> bytes:
    raw = ctypes.create_string_buffer(data)
    source = _Blob(len(data), ctypes.cast(raw, ctypes.POINTER(ctypes.c_ubyte)))
    target = _Blob()
    function = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise OSError("Windows не смог защитить ключ GitHub")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.windll.kernel32.LocalFree(target.data)


def save_token(token: str) -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    path = DATA / "github.dpapi"
    if token.strip():
        path.write_bytes(_protect(token.strip().encode()))
    else:
        path.unlink(missing_ok=True)


def github_token() -> str:
    path = DATA / "github.dpapi"
    if path.exists():
        return _protect(path.read_bytes(), True).decode()
    try:
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never")
        result = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
            text=True, capture_output=True, timeout=8, env=env, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        for line in result.stdout.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1]
    except (OSError, subprocess.TimeoutExpired):
        pass
    raise RuntimeError("Release publishing requires a GitHub credential; application updates are anonymous.")


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        url = urlparse(newurl)
        if url.scheme != "https" or not (url.hostname == "github.com" or url.hostname == "api.github.com" or (url.hostname or "").endswith(".githubusercontent.com")):
            raise ValueError("Недопустимый адрес скачивания GitHub")
        next_request = super().redirect_request(req, fp, code, msg, headers, newurl)
        if next_request and url.hostname != "api.github.com":
            next_request.remove_header("Authorization")
        return next_request


def github_open(path: str, token: str, binary: bool = False):
    if not path.startswith("/") or ".." in path or "?" in path and not path.startswith("/releases?"):
        raise ValueError("Invalid API path")
    headers={"User-Agent":"CameraDeck/"+VERSION,"Accept":"application/octet-stream" if binary else "application/vnd.github+json"}
    if token:headers["Authorization"]="Bearer "+token
    request = urllib.request.Request(API + path, headers=headers)
    return urllib.request.build_opener(SafeRedirect()).open(request, timeout=30)


def verify_envelope(envelope: bytes, public_key: bytes | None = None, current_code: int = VERSION_CODE) -> dict:
    if len(envelope) > 65536:
        raise ValueError("Manifest too large")
    signed = json.loads(envelope)
    payload = base64.b64decode(signed["payload"], validate=True)
    signature = base64.b64decode(signed["signature"], validate=True)
    key = serialization.load_pem_public_key(public_key or (ROOT / "client-update-public.pem").read_bytes())
    key.verify(signature, payload, padding.PKCS1v15(), hashes.SHA256())
    data = json.loads(payload)
    if data.get("product") != PRODUCT or data.get("protocol") != 1 or type(data.get("code")) is not int or data["code"] <= current_code:
        raise ValueError("Wrong product, protocol or old update")
    if not re.fullmatch(r"\d+\.\d+\.\d+", data.get("version", "")):
        raise ValueError("Invalid version")
    platforms = [("windows", "exe"), ("android", "apk")]
    if "windowsPortable" in data:
        platforms.append(("windowsPortable", "zip"))
    for platform, extension in platforms:
        asset = data[platform]
        if not re.fullmatch(r"[A-Za-z0-9_.-]+\." + extension, asset["name"]) or not re.fullmatch(r"[0-9a-f]{64}", asset["sha256"]):
            raise ValueError("Invalid artifact metadata")
        if type(asset["size"]) is not int or not 0 < asset["size"] <= 250_000_000:
            raise ValueError("Invalid artifact size")
    return data


def check_updates() -> dict | None:
    token = ""
    with github_open("/releases?per_page=100", token) as response:
        raw = response.read(4_000_001)
    if len(raw) > 4_000_000:
        raise ValueError("Too many releases")
    candidates = []
    for release in json.loads(raw):
        if release.get("draft") or not release.get("tag_name", "").startswith("camera-deck-client-v"):
            continue
        assets = {asset["name"]: asset for asset in release.get("assets", [])}
        manifest = assets.get("camera-deck-update.json")
        if not manifest:
            continue
        with github_open(f"/releases/assets/{int(manifest['id'])}", token, True) as response:
            envelope = response.read(65537)
        try:
            data = verify_envelope(envelope, current_code=0)
        except Exception:
            # A malformed release must not hide an otherwise valid newer release.
            continue
        for platform in ("windows", "android", *(["windowsPortable"] if "windowsPortable" in data else [])):
            meta = data[platform]
            asset = assets.get(meta["name"])
            if not asset or asset["size"] != meta["size"]:
                break
            meta["assetId"] = int(asset["id"])
        else:
            data["envelope"] = envelope
            candidates.append(data)
    return max(candidates, key=lambda item: item["code"], default=None)


def download_update(update: dict, platform: str) -> Path:
    # Re-verify the envelope; do not trust UI objects or unsigned metadata.
    signed = verify_envelope(update["envelope"], current_code=0)
    meta = signed[platform]
    if any(meta[k] != update[platform][k] for k in ("name", "sha256", "size")):
        raise ValueError("Update metadata changed")
    folder = DATA / "updates" / str(signed["code"])
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / meta["name"]
    def cached():
        if target.is_file() and target.stat().st_size==meta['size']:
            with target.open('rb') as existing:return hashlib.file_digest(existing,'sha256').hexdigest()==meta['sha256']
        return False
    with _download_commit:
        if cached():return target
    fd,temp_name=tempfile.mkstemp(prefix=target.name+'.',suffix='.partial',dir=folder)
    temp=Path(temp_name)
    try:
        with os.fdopen(fd,'wb') as output, github_open(f"/releases/assets/{int(update[platform]['assetId'])}", "", True) as response:
            total = 0
            digest = hashlib.sha256()
            while chunk := response.read(262144):
                total += len(chunk)
                if total > meta["size"]:
                    raise ValueError("Update exceeds expected size")
                digest.update(chunk)
                output.write(chunk)
        if total != meta["size"] or digest.hexdigest() != meta["sha256"]:
            raise ValueError("Update download failed signature/hash validation")
        with _download_commit:
            if not cached():temp.replace(target)
        return target
    finally:
        temp.unlink(missing_ok=True)


def windows_platform() -> str:
    return "windowsPortable" if getattr(sys, "frozen", False) and Path(getattr(sys, "_MEIPASS", "")).resolve() == Path(sys.executable).resolve().parent / "_internal" else "windows"


def extract_portable(candidate: Path, destination: Path) -> Path:
    import zipfile
    from pathlib import PurePosixPath
    with zipfile.ZipFile(candidate) as archive:
        files = archive.infolist()
        if sum(item.file_size for item in files) > 1_500_000_000:
            raise ValueError("Portable archive exceeds size limit")
        for item in files:
            parts = PurePosixPath(item.filename).parts
            if ("\\" in item.filename or ":" in item.filename or ".." in parts or
                len(parts) < 2 or parts[0] != "CameraDeck-Portable" or
                parts[1] not in ("CameraDeck-Portable.exe", "_internal") or
                (item.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError("Unsafe portable archive member")
        archive.extractall(destination)
    root = destination / "CameraDeck-Portable"
    if not (root / "CameraDeck-Portable.exe").is_file() or not (root / "_internal/python313.dll").is_file():
        raise ValueError("Incomplete portable update")
    return root


def restart_with_update(candidate: Path, update: dict) -> None:
    if not getattr(sys, "frozen", False):
        raise RuntimeError("Автоустановка доступна в собранном EXE")
    manifest = verify_envelope(update["envelope"])
    platform = windows_platform()
    expected = manifest[platform]["sha256"]
    with candidate.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            raise ValueError("Update hash mismatch")
    def quote(value):
        return "'" + str(value).replace("'", "''") + "'"
    target = Path(sys.executable).resolve()
    if platform == "windowsPortable":
        staged = extract_portable(candidate, candidate.parent / "unpacked")
        backup = candidate.parent / "previous-portable"
        # Only the executable and bundled runtime are copied; preserve user files.
        # No recursive removal or moving of the user's application directory.
        script = f"""$ErrorActionPreference = 'Stop'
Wait-Process -Id {os.getpid()} -ErrorAction SilentlyContinue
$stage = {quote(staged)}
$target = {quote(target.parent)}
$backup = {quote(backup)}
New-Item -ItemType Directory -Path $backup -Force | Out-Null
Copy-Item -LiteralPath {quote(target)} -Destination (Join-Path $backup 'CameraDeck-Portable.exe') -Force
Copy-Item -LiteralPath (Join-Path $target '_internal') -Destination $backup -Recurse -Force
try {{
    Copy-Item -LiteralPath (Join-Path $stage '_internal') -Destination $target -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $stage 'CameraDeck-Portable.exe') -Destination {quote(target)} -Force
}} catch {{
    Copy-Item -LiteralPath (Join-Path $backup '_internal') -Destination $target -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $backup 'CameraDeck-Portable.exe') -Destination {quote(target)} -Force
    throw
}}
Start-Process -FilePath {quote(target)} -WindowStyle Hidden
"""
        _launch_updater(script)
        return
    # Keep the previous executable for recovery; wait for this process to exit.
    script = f"""$ErrorActionPreference = 'Stop'
Wait-Process -Id {os.getpid()} -ErrorAction SilentlyContinue
$source = {quote(candidate)}
$target = {quote(target)}
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne '{expected}') {{ throw 'Update hash mismatch' }}
Copy-Item -LiteralPath $target -Destination ($target + '.previous') -Force
Copy-Item -LiteralPath $source -Destination ($target + '.new') -Force
$replaced = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {{
    try {{
        Move-Item -LiteralPath ($target + '.new') -Destination $target -Force -ErrorAction Stop
        $replaced = $true
        break
    }} catch {{ Start-Sleep -Seconds 1 }}
}}
if (-not $replaced) {{ throw 'Executable is still locked; previous version retained' }}
$ack = Join-Path (Split-Path -Parent $target) 'boot-ack.json'
$state = Join-Path (Split-Path -Parent $target) 'update-state.json'
Remove-Item -LiteralPath $ack -ErrorAction SilentlyContinue
$startedAt = Get-Date
$next = Start-Process -FilePath $target -WindowStyle Hidden -PassThru
$healthy = $false
for ($check = 0; $check -lt 45; $check++) {{
    Start-Sleep -Seconds 1
    if (Test-Path -LiteralPath $ack) {{
        try {{ $boot = Get-Content -LiteralPath $ack -Raw | ConvertFrom-Json; if ($boot.code -eq {manifest['code']}) {{ $healthy=$true; break }} }} catch {{}}
    }}
    if ($next.HasExited) {{ break }}
}}
if (-not $healthy) {{
    Get-CimInstance Win32_Process -Filter "Name='CameraDeck.exe'" | Where-Object {{ $_.ExecutablePath -eq $target -and $_.CreationDate -ge $startedAt }} | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }}
    for ($retry=0; $retry -lt 10; $retry++) {{
        try {{ Copy-Item -LiteralPath ($target + '.previous') -Destination $target -Force -ErrorAction Stop; break }} catch {{ Start-Sleep -Seconds 1 }}
    }}
    @{{ failedCode={manifest['code']}; rolledBack=$true }} | ConvertTo-Json | Set-Content -LiteralPath $state -Encoding UTF8
    Start-Process -FilePath $target -WindowStyle Hidden
}} else {{
    @{{ installedCode={manifest['code']}; rolledBack=$false }} | ConvertTo-Json | Set-Content -LiteralPath $state -Encoding UTF8
}}
"""
    _launch_updater(script)


def _launch_updater(script: str) -> None:
    fd, script_path = tempfile.mkstemp(prefix="camera-deck-update-", suffix=".ps1")
    with os.fdopen(fd, "w", encoding="utf-8-sig") as out:
        out.write(script)
    subprocess.Popen(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script_path],
                     creationflags=subprocess.CREATE_NO_WINDOW)
