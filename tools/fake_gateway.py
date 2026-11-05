#!/usr/bin/env python3
"""A stand-in for the COSMO GTW gateway, for trying the app and CLI without the hardware.

Login is admin / secret. Run it and point the client at this machine (10.0.2.2 from an emulator).
It only implements what the clients use: MQTT 3.1.1 over WebSocket, at QoS 0, for one gateway.
Needs only Python and the openssl command.
"""
import argparse
import base64
import hashlib
import os
import socket
import socketserver
import struct
import subprocess
import threading
import time

USER = "admin"
USER_KEY = hashlib.sha256(b"secret").digest()
PRIVATE_KEY = os.urandom(32)
PUBLIC_KEY = os.urandom(32)
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# id, name, type, favourite. Types: 2 = COSMO, 1 = SENSO (has positions), 4 = garage (hidden by clients).
DEVICES = [
    (0, "Kitchen", 2, False),
    (1, "Bedroom", 1, True),
    (2, "Office", 2, False),
    (3, "Garage", 4, False),
]
state = {0: "UP", 1: "40%", 2: "DOWN", 3: "DOWN"}
clients = []
lock = threading.Lock()


def varint(n):
    out = bytearray()
    while n > 0x7F:
        out.append(n & 0x7F | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def field_int(field, n):
    return varint(field << 3) + varint(n)


def field_bytes(field, data):
    return varint(field << 3 | 2) + varint(len(data)) + data


def parse_proto(data):
    fields, i = [], 0

    def read_varint():
        nonlocal i
        result = shift = 0
        while True:
            b = data[i]
            i += 1
            result |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return result

    while i < len(data):
        tag = read_varint()
        if tag & 7 == 0:
            fields.append((tag >> 3, read_varint()))
        elif tag & 7 == 2:
            size = read_varint()
            fields.append((tag >> 3, data[i:i + size]))
            i += size
        else:
            raise ValueError("unsupported wire type")
    return fields


def crypt(key, timestamp, data, encrypt):
    iv = b"\0" * 12 + struct.pack(">I", timestamp)
    mode = "-e" if encrypt else "-d"
    command = ["openssl", "enc", "-aes-256-cfb", mode, "-K", key.hex(), "-iv", iv.hex(), "-nosalt"]
    return subprocess.run(command, input=data, capture_output=True, check=True).stdout


def frame(category, key, body):
    timestamp = int(time.time())
    header = struct.pack(">IBI6sBB", 13, category, timestamp, b"\0" * 6, 1, 0)
    return header + crypt(key, timestamp, body, True)


class Client(socketserver.BaseRequestHandler):
    def handle(self):
        self.subscriptions = set()
        self.buffer = b""
        self.send_lock = threading.Lock()
        try:
            self.websocket_handshake()
            with lock:
                clients.append(self)
            while True:
                self.mqtt_packet()
        except (ConnectionError, EOFError, OSError):
            pass
        finally:
            with lock:
                if self in clients:
                    clients.remove(self)

    def websocket_handshake(self):
        request = b""
        while b"\r\n\r\n" not in request:
            chunk = self.request.recv(4096)
            if not chunk:
                raise EOFError
            request += chunk
        lines = request.decode().split("\r\n")
        headers = {k.lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
        print("connect:", lines[0], "protocol:", headers.get("sec-websocket-protocol"), flush=True)
        accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + GUID).encode()).digest()).decode()
        self.request.sendall(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n".encode()
        )

    def read_exact(self, n):
        out = b""
        while len(out) < n:
            chunk = self.request.recv(n - len(out))
            if not chunk:
                raise EOFError
            out += chunk
        return out

    def read_ws_frame(self):
        b0, b1 = self.read_exact(2)
        size = b1 & 0x7F
        if size == 126:
            size = struct.unpack(">H", self.read_exact(2))[0]
        elif size == 127:
            size = struct.unpack(">Q", self.read_exact(8))[0]
        mask = self.read_exact(4) if b1 & 0x80 else b"\0" * 4
        data = bytes(c ^ mask[i % 4] for i, c in enumerate(self.read_exact(size)))
        if b0 & 0x0F == 8:
            raise EOFError
        self.buffer += data

    def take(self, n):
        while len(self.buffer) < n:
            self.read_ws_frame()
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def send_ws(self, data):
        size = len(data)
        if size < 126:
            head = bytes([0x82, size])
        elif size < 65536:
            head = bytes([0x82, 126]) + struct.pack(">H", size)
        else:
            head = bytes([0x82, 127]) + struct.pack(">Q", size)
        with self.send_lock:
            self.request.sendall(head + data)

    def mqtt_packet(self):
        first = self.take(1)[0]
        size = shift = 0
        while True:
            b = self.take(1)[0]
            size |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        body = self.take(size)
        kind = first >> 4
        if kind == 1:  # CONNECT
            self.send_ws(b"\x20\x02\x00\x00")
        elif kind == 8:  # SUBSCRIBE
            i = 2
            granted = b""
            while i < len(body):
                n = struct.unpack(">H", body[i:i + 2])[0]
                self.subscriptions.add(body[i + 2:i + 2 + n].decode())
                i += 3 + n
                granted += b"\0"
            self.send_ws(bytes([0x90, 2 + len(granted)]) + body[:2] + granted)
        elif kind == 3:  # PUBLISH
            n = struct.unpack(">H", body[:2])[0]
            if body[2:2 + n] == b"module":
                self.on_message(body[2 + n:])
        elif kind == 12:  # PINGREQ
            self.send_ws(b"\xd0\x00")
        elif kind == 14:  # DISCONNECT
            raise EOFError

    def publish(self, topic, payload):
        name = topic.encode()
        rest = struct.pack(">H", len(name)) + name + payload
        length, n = b"", len(rest)
        while True:
            b = n & 0x7F
            n >>= 7
            length += bytes([b | 0x80 if n else b])
            if not n:
                break
        self.send_ws(b"\x30" + length + rest)

    def on_message(self, message):
        _, category, timestamp, client_id, _, _ = struct.unpack(">IBI6sBB", message[:17])
        body = message[17:]
        own_topic = client_id.hex().upper()
        if category == 1:
            fields = dict(parse_proto(body))
            ok = fields.get(1) == USER.encode() and fields.get(2) == USER_KEY
            print("login:", "ok" if ok else "rejected", flush=True)
            response = field_int(1, 0) + field_bytes(2, PRIVATE_KEY) + field_bytes(3, PUBLIC_KEY) if ok else field_int(1, 1)
            self.publish(own_topic, frame(2, USER_KEY, response))
        elif category == 3:
            devices = b""
            for device_id, name, kind, favourite in DEVICES:
                device = field_int(1, device_id) + field_bytes(2, name.encode()) + field_int(3, kind) + field_int(6, favourite)
                devices += field_bytes(1, device)
            self.publish(own_topic, frame(4, PRIVATE_KEY, devices))
        elif category == 26:
            events = b""
            for device_id, value in state.items():
                events += field_bytes(1, field_int(2, device_id) + field_int(3, 8) + field_bytes(4, value.encode()))
            self.publish(own_topic, frame(27, PRIVATE_KEY, events))
        elif category == 13:
            plain = crypt(PRIVATE_KEY, timestamp, body, False)
            for _, event in parse_proto(plain):
                fields = dict(parse_proto(event))
                if fields[2] not in state:
                    continue
                value = fields[4].decode()
                print(f"command: device {fields[2]} -> {value}", flush=True)
                if value != "STOP":
                    state[fields[2]] = value
            echo = frame(13, PUBLIC_KEY, plain)
            with lock:
                for client in clients:
                    if "clients" in client.subscriptions:
                        client.publish("clients", echo)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    address_family = socket.AF_INET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8884)
    parser.add_argument("--bind", default="127.0.0.1")
    args = parser.parse_args()
    with Server((args.bind, args.port), Client) as server:
        print(f"fake gateway on {args.bind}:{args.port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
