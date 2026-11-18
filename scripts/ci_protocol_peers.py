"""Loopback-only protocol peers for Android emulator instrumentation."""
import base64
import hashlib
import http.server
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import uuid


def prepare(directory, xray, singbox):
    cert, key = (str(directory / n) for n in ("cert.pem", "key.pem"))
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", "/CN=localhost", "-keyout", key, "-out", cert], check=True,
                   stderr=subprocess.DEVNULL)
    digest = hashlib.sha256(subprocess.check_output(
        ["openssl", "x509", "-in", cert, "-outform", "DER"])).hexdigest()
    tls = {"certificates": [{"certificateFile": cert, "keyFile": key}]}
    uid, password = str(uuid.uuid4()), secrets.token_hex(16)
    host = "10.0.2.2"
    x_in, s_in, cases = [], [], {}
    keys = subprocess.check_output([xray, "x25519"], text=True).splitlines()
    private, public = [line.split(":", 1)[1].strip() for line in keys[:2]]
    sid = secrets.token_hex(4)

    for index, (name, network, security) in enumerate([
        ("VLESS-TCP", "raw", "none"), ("VLESS-WS", "ws", "none"),
        ("VLESS-gRPC", "grpc", "none"), ("VLESS-XHTTP", "xhttp", "none"),
        ("VLESS-HTTPUpgrade", "httpupgrade", "none"), ("VLESS-mKCP", "mkcp", "none"),
        ("VLESS-Vision-REALITY", "raw", "reality"), ("VLESS-XHTTP-REALITY", "xhttp", "reality"),
        ("VLESS-XHTTP-H3", "xhttp", "tls"),
    ]):
        port = 34100 + index
        stream = {"network": network, "security": security}
        query = f"type={network}&security={security}"
        user = {"id": uid}
        if network in ("ws", "xhttp", "httpupgrade"):
            stream[network + "Settings"] = {"path": "/phantom"}
            query += "&path=%2Fphantom"
        if network == "grpc":
            stream["grpcSettings"] = {"serviceName": "phantom"}
            query += "&serviceName=phantom"
        if security == "reality":
            stream["realitySettings"] = {"target": "127.0.0.1:34302", "serverNames": ["localhost"],
                                         "privateKey": private, "shortIds": [sid]}
            query += f"&sni=localhost&pbk={public}&sid={sid}&fp=chrome"
            if network == "raw":
                user["flow"] = "xtls-rprx-vision"
                query += "&flow=xtls-rprx-vision"
        if security == "tls":
            stream["tlsSettings"] = {**tls, "alpn": ["h3"]}
            query += f"&sni=localhost&alpn=h3&pcs={digest}"
        x_in.append({"port": port, "listen": "127.0.0.1", "protocol": "vless",
                     "settings": {"clients": [user], "decryption": "none"}, "streamSettings": stream})
        cases[name] = f"vless://{uid}@{host}:{port}?{query}"
    # Use independent implementations for the remaining protocols.
    for offset, protocol in enumerate(("vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls")):
        port = 34200 + offset
        inbound = {"type": protocol, "listen": "127.0.0.1", "listen_port": port}
        if protocol == "vmess":
            inbound["users"] = [{"uuid": uid}]
            profile = {"v": "2", "ps": "VMess", "add": host, "port": str(port), "id": uid, "net": "tcp"}
            link = "vmess://" + base64.b64encode(json.dumps(profile).encode()).decode()
        elif protocol == "shadowsocks":
            inbound.update(method="aes-128-gcm", password=password)
            auth = base64.urlsafe_b64encode(f"aes-128-gcm:{password}".encode()).decode().rstrip("=")
            link = f"ss://{auth}@{host}:{port}"
        else:
            inbound["tls"] = {"enabled": True, "certificate_path": cert, "key_path": key}
            inbound["users"] = [{"password": password}]
            auth = password
            if protocol == "tuic":
                inbound["users"][0]["uuid"] = uid
                auth = uid + ":" + password
            if protocol in ("tuic", "hysteria2"):
                inbound["tls"]["alpn"] = ["h3"]
            query = "security=tls&sni=localhost"
            if protocol in ("tuic", "anytls"):
                query += "&insecure=1"
            else:
                query += "&pcs=" + digest
            link = f"{protocol}://{auth}@{host}:{port}?{query}"
        s_in.append(inbound)
        cases[protocol] = link
    for file, config in (("x.json", {"inbounds": x_in, "outbounds": [{"protocol": "freedom"}]}),
                         ("s.json", {"inbounds": s_in, "outbounds": [{"type": "direct"}]})):
        (directory / file).write_text(json.dumps(config))
    return cases, [
        [xray, "run", "-c", str(directory / "x.json")],
        [singbox, "run", "-c", str(directory / "s.json")],
        ["openssl", "s_server", "-accept", "127.0.0.1:34302", "-cert", cert, "-key", key,
         "-alpn", "h2,http/1.1", "-www", "-quiet"],
    ]


class Probe(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(.05)
        self.send_response(204)
        self.end_headers()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as temporary:
        cases, commands = prepare(Path(temporary), *sys.argv[1:3])
        children = []
        try:
            for command in commands:
                children.append(subprocess.Popen(command))
            time.sleep(1)
            if any(child.poll() is not None for child in children):
                raise RuntimeError("A protocol peer failed to start")
            path = Path(sys.argv[3])
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(cases))
            http.server.ThreadingHTTPServer(("127.0.0.1", 34301), Probe).serve_forever()
        finally:
            for child in children:
                child.terminate()
