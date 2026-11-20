"""Loopback-only protocol peers for Android emulator instrumentation."""
import base64
import hashlib
import http.server
import json
from pathlib import Path
import secrets
import socket
import socketserver
import struct
import threading
import subprocess
import sys
import tempfile
import time
import uuid


def provider_balance(uid, host, port, stream):
    def outbound(tag, address, endpoint_port):
        return {"tag": tag, "protocol": "vless",
                "settings": {"vnext": [{"address": address, "port": endpoint_port,
                                       "users": [{"id": uid, "encryption": "none"}]}]},
                "streamSettings": stream}

    return [{
        "remarks": "Автовыбор",
        "inbounds": [
            {"tag": "monitor", "protocol": "dokodemo-door"},
            {"tag": "client", "protocol": "socks",
             "sniffing": {"enabled": True, "routeOnly": True, "destOverride": ["http", "tls", "quic"]}},
        ],
        "outbounds": [
            {"tag": "block", "protocol": "blackhole"},
            outbound("pool-offline", "127.0.0.1", 34999),
            {**outbound("fallback", "entry.phantom.test", port),
             "streamSettings": {**stream, "sockopt": {"domainStrategy": "ForceIPv4"}}},
            {"tag": "lan-dns", "protocol": "freedom", "settings": {"finalRules": [
                {"action": "allow", "network": "udp", "ip": [host + "/32"], "port": 34303},
                {"action": "block"}]}},
        ],
        "dns": {"tag": "provider-dns", "queryStrategy": "UseIPv4",
                "hosts": {"through-tun.phantom.test": "198.18.0.1"},
                "servers": [{"address": host, "port": 34303}]},
        "burstObservatory": {"subjectSelector": ["pool-"], "pingConfig": {
            "destination": "http://127.0.0.1:34301/Observe",
            "interval": "10s", "timeout": "1s", "sampling": 1,
        }},
        "routing": {
            "balancers": [{"tag": "auto", "selector": ["pool-"], "fallbackTag": "fallback",
                           "strategy": {"type": "leastLoad"}}],
            "rules": [
                {"type": "field", "inboundTag": ["monitor"], "outboundTag": "block"},
                {"type": "field", "inboundTag": ["provider-dns"], "outboundTag": "lan-dns"},
                {"type": "field", "inboundTag": ["client"], "balancerTag": "auto"},
            ],
        },
    }]


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
    balance = None
    keys = subprocess.check_output([xray, "x25519"], text=True).splitlines()
    private, public = [line.split(":", 1)[1].strip() for line in keys[:2]]
    sid = secrets.token_hex(4)

    for index, (name, network, security) in enumerate([
        ("VLESS-TCP", "raw", "none"), ("VLESS-WS", "ws", "none"),
        ("VLESS-gRPC", "grpc", "none"), ("VLESS-XHTTP", "xhttp", "none"),
        ("VLESS-HTTPUpgrade", "httpupgrade", "none"), ("VLESS-mKCP", "mkcp", "none"),
        ("VLESS-Vision-REALITY", "raw", "reality"), ("VLESS-XHTTP-REALITY", "xhttp", "reality"),
        ("VLESS-XHTTP-H3", "xhttp", "tls"),
        ("VLESS-TCP-HTTP", "raw", "none"),
    ]):
        port = 34100 + index
        stream = {"network": network, "security": security}
        query = f"type={network}&security={security}"
        user = {"id": uid}
        if name == "VLESS-TCP-HTTP":
            stream["rawSettings"] = {"header": {"type": "http", "request": {"path": ["/phantom"]}}}
            query += "&headerType=http&path=%2Fphantom"
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
        inbound = {"port": port, "listen": "127.0.0.1", "protocol": "vless",
                   "settings": {"clients": [user], "decryption": "none"}, "streamSettings": stream}
        if name == "VLESS-XHTTP":
            inbound["tag"] = "provider-xhttp"
            balance = provider_balance(uid, host, port, stream)
        x_in.append(inbound)
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
            if protocol == "tuic":
                query += "&alpn=h3"
            if protocol in ("tuic", "anytls"):
                query += "&insecure=1"
            else:
                query += "&pcs=" + digest
            link = f"{protocol}://{auth}@{host}:{port}?{query}"
        s_in.append(inbound)
        cases[protocol] = link
    # All peers bind only to runner loopback. Limit egress to the test origin
    # and, on the XHTTP peer, Phantom's certificate-checked HTTPS probes.
    origin_only = {"protocol": "freedom", "settings": {"finalRules": [
        {"action": "allow", "ip": ["127.0.0.1/32"], "port": "34301", "network": "tcp"},
        {"action": "block"}]}}
    connectivity = {"tag": "connectivity", "protocol": "freedom"}
    transfer = {"tag": "transfer", "protocol": "freedom", "settings": {
        "redirect": "127.0.0.1:34301",
        # Xray applies its private-address guard after rewriting the destination.
        # Allow just this loopback origin, as for the other test peers.
        "finalRules": [
            {"action": "allow", "ip": ["127.0.0.1/32"], "port": "34301", "network": "tcp"},
            {"action": "block"},
        ],
    }}
    xray_server = {"inbounds": x_in, "outbounds": [origin_only, connectivity, transfer],
                   "routing": {"rules": [
                       {"type": "field", "inboundTag": ["provider-xhttp"], "network": "tcp",
                        "ip": ["198.18.0.1"], "port": "34301", "outboundTag": "transfer"},
                       {"type": "field", "inboundTag": ["provider-xhttp"], "network": "tcp",
                        "port": "443", "domain": ["full:www.gstatic.com", "full:cp.cloudflare.com",
                                                 "full:example.com"], "outboundTag": "connectivity"},
                   ]}}
    for file, config in (("x.json", xray_server),
                         ("s.json", {"inbounds": s_in, "outbounds": [{"type": "direct"}],
                                     "route": {"rules": [{"port": [34304], "action": "reject"}]}})):
        (directory / file).write_text(json.dumps(config))
    return cases, balance, [
        [xray, "run", "-c", str(directory / "x.json")],
        [singbox, "run", "-c", str(directory / "s.json")],
        ["openssl", "s_server", "-accept", "127.0.0.1:34302", "-cert", cert, "-key", key,
         "-alpn", "h2,http/1.1", "-www", "-quiet"],
    ]


class DnsPeer(socketserver.BaseRequestHandler):
    def handle(self):
        query, sock = self.request
        question = b"\x05entry\x07phantom\x04test\x00"
        if len(query) < 12 or not query[12:].startswith(question):
            return
        end = 12 + len(question) + 4
        is_a = query[end - 4:end] == b"\x00\x01\x00\x01"
        reply = query[:2] + b"\x81\x80" + struct.pack("!4H", 1, int(is_a), 0, 0) + query[12:end]
        if is_a:
            reply += bytes.fromhex("c00c000100010000001e0004") + socket.inet_aton("10.0.2.2")
        sock.sendto(reply, self.client_address)


class TcpEntry(socketserver.BaseRequestHandler):
    empty = 0
    payloads = 0
    lock = threading.Lock()

    def handle(self):
        self.request.settimeout(5)
        try:
            data = self.request.recv(1)
        except OSError:
            return
        with self.lock:
            if data:
                TcpEntry.payloads += 1
            else:
                TcpEntry.empty += 1


class Probe(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/TcpEntryCounts":
            with TcpEntry.lock:
                body = json.dumps([TcpEntry.empty, TcpEntry.payloads]).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        time.sleep(.05)
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        if self.path != "/VpnTransfer" or self.headers.get("Content-Length") != "1048576":
            self.send_error(400)
            return
        received = self.rfile.read(1048576)
        if len(received) != 1048576:
            self.send_error(400)
            return
        body = hashlib.sha256(received).digest() * 32768
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as temporary:
        cases, balance, commands = prepare(Path(temporary), *sys.argv[1:3])
        children = []
        try:
            for command in commands:
                children.append(subprocess.Popen(command))
            time.sleep(1)
            if any(child.poll() is not None for child in children):
                raise RuntimeError("A protocol peer failed to start")
            path = Path(sys.argv[3])
            path.parent.mkdir(parents=True, exist_ok=True)
            path.with_name("provider-balance.json").write_text(json.dumps(balance))
            observed = json.loads(json.dumps(balance))
            config = observed[0]
            config.pop("burstObservatory")
            config["observatory"] = {"subjectSelector": ["live-"], "enableConcurrency": True,
                                     "probeURL": "http://127.0.0.1:34301/Observe", "probeInterval": "10s"}
            config["outbounds"][2]["tag"] = "live-secondary"
            config["routing"]["balancers"] = [{"tag": "auto", "selector": ["live-"],
                                               "strategy": {"type": "leastPing"}}]
            path.with_name("observed-balance.json").write_text(json.dumps(observed))
            path.write_text(json.dumps(cases))
            for server in (socketserver.UDPServer(("127.0.0.1", 34303), DnsPeer),
                           socketserver.ThreadingTCPServer(("127.0.0.1", 34304), TcpEntry)):
                threading.Thread(target=server.serve_forever, daemon=True).start()
            http.server.ThreadingHTTPServer(("127.0.0.1", 34301), Probe).serve_forever()
        finally:
            for child in children:
                child.terminate()
