"""Local protocol. TLS identity is checked BEFORE sending the PIN or token."""
from __future__ import annotations
import hashlib
import http.client
import ipaddress
import json
import socket
import ssl
import time
import threading
from pathlib import Path
from urllib.parse import urlencode

DISCOVERY_PORT = 39811
MAX_JSON = 1_000_000


def local_host(host: str) -> str:
    address = ipaddress.ip_address(host)
    if address.version != 4 or not address.is_private or address.is_loopback or address.is_unspecified or address.is_multicast:
        raise ValueError("Укажите локальный IPv4-адрес телефона")
    return str(address)


def discover(seconds: float = 2.0) -> list[dict]:
    devices = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("0.0.0.0", 0))
        sock.settimeout(0.2)
        targets = {"255.255.255.255"}
        try:
            # Limited broadcast plus interface broadcasts, including /20 and /23 LANs.
            import subprocess
            result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-NetIPAddress -AddressFamily IPv4 | Select-Object IPAddress,PrefixLength | ConvertTo-Json -Compress"],
                capture_output=True, text=True, timeout=5, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            interfaces = json.loads(result.stdout or "[]")
            for interface in interfaces if isinstance(interfaces, list) else [interfaces]:
                network = ipaddress.ip_network(f"{interface['IPAddress']}/{interface['PrefixLength']}", strict=False)
                if network.is_private and not network.is_loopback:
                    targets.add(str(network.broadcast_address))
        except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
            pass
        for target in targets:
            try:
                sock.sendto(b"CAMERA_DECK_DISCOVER_V1", (target, DISCOVERY_PORT))
            except OSError:
                pass
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                data, (host, _) = sock.recvfrom(4096)
                item = json.loads(data)
                if not isinstance(item,dict):continue
                fp = item.get("fingerprint", "")
                if item.get("protocol") != 1 or not isinstance(fp,str) or len(fp) != 64 or any(c not in "0123456789abcdef" for c in fp):
                    continue
                if item.get("port") != 39812 or not isinstance(item.get("id"), str):
                    continue
                item["host"] = local_host(host)
                item["name"] = str(item.get("name", "Камера"))[:60]
                devices[item["id"]] = item
            except (socket.timeout, ValueError, KeyError, TypeError):
                continue
    return list(devices.values())


class CameraClient:
    def __init__(self, host: str, fingerprint: str = "", *, port: int = 39812, transport: str = "lan"):
        if transport not in ('lan','usb'):raise ValueError('Unknown transport')
        if transport=='usb' and host!='127.0.0.1':raise ValueError('USB tunnel must use loopback')
        if not isinstance(port,int) or isinstance(port,bool) or not 1<=port<=65535:raise ValueError('Invalid port')
        if transport=='lan' and port!=39812:raise ValueError('Unexpected LAN port')
        self.host = host if transport=='usb' else local_host(host)
        self.port=port;self.transport=transport
        self.fingerprint = fingerprint.replace(" ", "").lower()
        self.token = ""
        self.refresh_secret="";self.session_lock=threading.Lock()

    def connection(self, probe: bool = False) -> http.client.HTTPSConnection:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # explicit certificate pin below
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        conn = http.client.HTTPSConnection(self.host, self.port, timeout=8, context=context)
        try:
            try:
                conn.connect()
            except OSError as error:
                raise ConnectionError(connection_message(error)) from error
            actual = hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest()
            if not probe and (not self.fingerprint or actual != self.fingerprint):
                raise ssl.SSLError("Сертификат телефона изменился. Сравните отпечаток на телефоне и подключитесь заново.")
            if probe:
                self.fingerprint = actual
            return conn
        except Exception:
            conn.close()
            raise


    def probe(self) -> str:
        conn = self.connection(probe=True)
        conn.close()
        return self.fingerprint

    def frames(self, stopped, enabled):
        """One pinned TLS stream. Only one bounded JPEG is held by this reader."""
        conn=self.connection()
        try:
            conn.sock.settimeout(4)
            conn.request("GET","/stream",headers={"Authorization":"Bearer "+self.token})
            response=conn.getresponse()
            if response.status==404:raise StreamUnavailable("Телефону требуется обновление потокового просмотра")
            if response.status!=200:raise ConnectionError(f"Просмотр: HTTP {response.status}")
            if "multipart/x-mixed-replace" not in response.getheader("Content-Type",""):raise ValueError("Неверный формат видеопотока")
            while not stopped.is_set() and enabled.is_set():
                line=response.readline(128)
                if not line:raise ConnectionError("Видеопоток завершён")
                if line==b"\r\n":continue
                if line.strip()!=b"--aurora":raise ValueError("Повреждена граница видеопотока")
                headers={};total=0
                while True:
                    line=response.readline(4096);total+=len(line)
                    if not line or total>16384:raise ValueError("Повреждены заголовки видеопотока")
                    if line==b"\r\n":break
                    key,value=line.decode("ascii").split(":",1);headers[key.lower()]=value.strip()
                size=int(headers.get("content-length","0"))
                if not 0<size<=2_000_000:raise ValueError("Недопустимый размер видеокадра")
                frame=response.read(size)
                if len(frame)!=size:raise ConnectionError("Видеокадр получен не полностью")
                yield frame,int(headers.get("x-rotation","0"))%360
        finally:conn.close()

    def request(self, path: str, method: str = "GET", body: bytes | None = None,
                extra: dict | None = None, limit: int = MAX_JSON, _retried=False) -> bytes:
        conn = self.connection()
        try:
            headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
            headers.update(extra or {})
            conn.request(method, path, body=body, headers=headers)
            response = conn.getresponse()
            if response.status==401 and self.refresh_secret and path!='/resume' and not _retried:
                previous=headers['Authorization'][7:];conn.close();self.renew(previous)
                return self.request(path,method,body,extra,limit,True)
            result = response.read(limit + 1)
            if len(result) > limit:
                raise ValueError("Ответ телефона слишком большой")
            if response.status != 200:
                try:
                    message = json.loads(result).get("error", str(response.status))
                except ValueError:
                    message = f"HTTP {response.status}"
                raise ConnectionError(message)
            return result
        finally:
            conn.close()

    def json(self, path: str, body: dict | None = None) -> dict:
        return json.loads(self.request(path, "POST" if body is not None else "GET",
                                       json.dumps(body).encode() if body is not None else None))

    def pair(self, pin: str, remember: bool = False) -> dict:
        if len(pin) != 4 or not pin.isascii() or not pin.isdigit():
            raise ValueError("PIN состоит из четырёх цифр")
        result = self.json("/pair", {"pin": pin,"remember":remember,"clientName":socket.gethostname()[:60]})
        self.token = result["token"]
        self.refresh_secret=result.get("rememberToken","")
        return result

    def resume(self, secret):
        result=self.json("/resume",{"rememberToken":secret});self.token=result["token"];self.refresh_secret=secret;return result

    def renew(self, previous):
        with self.session_lock:
            if self.token==previous:self.resume(self.refresh_secret);self.force_avc=True

    def command(self, action: str, **kwargs) -> dict:
        result=self.json("/command", {"action": action, **kwargs})
        if action in ('camera','stream_config'):self.force_avc=True
        return result

    def download(self, filename: str, folder: Path, progress=None) -> Path:
        import re
        if not re.fullmatch(r"CD_\d+_[a-f0-9]{8}\.(jpg|png|webp|dng|mp4)", filename):
            raise ValueError("Недопустимое имя файла")
        target = folder / filename
        if target.exists():
            raise FileExistsError(f"Файл уже существует: {filename}")
        partial = target.with_suffix(target.suffix + ".partial")
        owns_partial=False
        conn = self.connection()
        try:
            conn.request("GET", "/file?" + urlencode({"name": filename}), headers={"Authorization": "Bearer " + self.token})
            response = conn.getresponse()
            if response.status != 200:
                raise ConnectionError(f"Скачивание: HTTP {response.status}")
            expected = int(response.getheader("Content-Length", "-1"))
            if expected < 1:
                raise ValueError("Неизвестный размер файла")
            count = 0
            with partial.open("xb") as out:
                owns_partial=True
                while chunk := response.read(256 * 1024):
                    out.write(chunk)
                    count += len(chunk)
                    if count>expected:raise ValueError("Файл превышает заявленный размер")
                    if progress:progress(count,expected)
            if count != expected:
                raise ValueError("Файл скачан не полностью")
            partial.rename(target)
            owns_partial=False
            return target
        finally:
            conn.close()
            if owns_partial:partial.unlink(missing_ok=True)

    def send_update(self, apk: Path, envelope: bytes) -> None:
        import base64
        conn = self.connection()
        try:
            conn.sock.settimeout(120)
            with apk.open("rb") as source:
                conn.request("PUT", "/update", body=source, headers={
                    "Authorization": "Bearer " + self.token, "Content-Length": str(apk.stat().st_size),
                    "Content-Type": "application/octet-stream",
                    "X-Update-Manifest": base64.b64encode(envelope).decode("ascii")})
                response = conn.getresponse()
                data = response.read(16384)
                if response.status != 200:
                    raise ConnectionError(json.loads(data).get("error", f"HTTP {response.status}"))
        finally:
            conn.close()


class StreamUnavailable(ConnectionError):
    pass


def connection_message(error: Exception) -> str:
    """Actionable connection failures without weakening TLS or certificate pinning."""
    if isinstance(error, ssl.SSLError):
        if "Сертификат телефона" in str(error):
            return str(error)
        return ("Телефон прервал защищённое подключение. Обновите Camera Deck на телефоне и компьютере, "
                "затем выключите и включите камеру на телефоне и повторите подключение.\n\n"
                "Если ошибка повторится, проверьте VPN и фильтрацию сети.\n"
                f"Подробности: {error}")
    if isinstance(error, (TimeoutError, OSError)):
        return ("Не удалось связаться с телефоном. Включите камеру в приложении, проверьте общую Wi-Fi сеть "
                "и IP-адрес. При необходимости разрешите Camera Deck в брандмауэре для частной сети.\n\n"
                f"Подробности: {error}")
    return str(error)
