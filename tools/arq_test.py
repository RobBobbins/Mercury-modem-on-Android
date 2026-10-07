"""Mercury ARQ connection test: PC Mercury calls phone Mercury, 256 bytes each
way, clean disconnect. Both modems are driven over their TCP control/data ports
from this PC; the phone's ports are reached through `adb forward`.

Usage: python arq_test.py <logfile>
"""
import socket
import sys
import threading
import time

PC = ("127.0.0.1", 8300)      # PC mercury.exe control; data is +1
PHONE = ("127.0.0.1", 9300)   # adb forward tcp:9300 -> phone 8400, 9301 -> 8401 (Mercury Modem app)
PC_CALL, PHONE_CALL = "VA3OSO", "VA3OZO"
NBYTES = 256
CONNECT_TIMEOUT = 120
TRANSFER_TIMEOUT = 240
DISCONNECT_TIMEOUT = 60

log_lock = threading.Lock()
t0 = time.time()
logf = open(sys.argv[1], "w", encoding="utf-8")


def log(msg):
    line = f"{time.time() - t0:7.2f}s {msg}"
    with log_lock:
        print(line, flush=True)
        logf.write(line + "\n")
        logf.flush()


class Modem:
    def __init__(self, name, addr):
        self.name = name
        self.ctl = socket.create_connection(addr, timeout=10)
        self.data = socket.create_connection((addr[0], addr[1] + 1), timeout=10)
        self.ctl.settimeout(None)
        self.data.settimeout(None)
        self.lines = []
        self.received = bytearray()
        self.cv = threading.Condition()
        threading.Thread(target=self._read_ctl, daemon=True).start()
        threading.Thread(target=self._read_data, daemon=True).start()

    def _read_ctl(self):
        buf = b""
        while True:
            try:
                chunk = self.ctl.recv(4096)
            except OSError:
                return
            if not chunk:
                return
            buf += chunk
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                text = line.decode("ascii", "replace").strip()
                if text:
                    if not text.startswith(("BUFFER", "PTT", "BUSY")):
                        log(f"{self.name} < {text}")
                    with self.cv:
                        self.lines.append(text)
                        self.cv.notify_all()

    def _read_data(self):
        while True:
            try:
                chunk = self.data.recv(4096)
            except OSError:
                return
            if not chunk:
                return
            with self.cv:
                self.received += chunk
                self.cv.notify_all()
            log(f"{self.name} data +{len(chunk)} bytes (total {len(self.received)})")

    def send(self, cmd):
        log(f"{self.name} > {cmd}")
        self.ctl.sendall(cmd.encode("ascii") + b"\r")

    def wait_line(self, prefix, timeout):
        end = time.time() + timeout
        with self.cv:
            while True:
                for i, l in enumerate(self.lines):
                    if l.startswith(prefix):
                        del self.lines[: i + 1]
                        return l
                left = end - time.time()
                if left <= 0:
                    return None
                self.cv.wait(left)

    def wait_bytes(self, n, timeout):
        end = time.time() + timeout
        with self.cv:
            while len(self.received) < n:
                left = end - time.time()
                if left <= 0:
                    break
                self.cv.wait(left)
            return bytes(self.received)


def payload(tag):
    # 256 distinct, recognisable bytes: tag text then a counting pattern.
    head = f"{tag} 256-byte test ".encode("ascii")
    body = bytes((i * 7 + 13) % 256 for i in range(NBYTES - len(head)))
    return head + body


def main():
    pc, phone = Modem("PC", PC), Modem("PHONE", PHONE)
    for m, call in ((pc, PC_CALL), (phone, PHONE_CALL)):
        m.send(f"MYCALL {call}")
        if m.wait_line("OK", 10) is None:
            log(f"FAIL: {m.name} did not answer MYCALL")
            return 1
    phone.send("LISTEN ON")
    if phone.wait_line("OK", 10) is None:
        log("FAIL: phone did not answer LISTEN ON")
        return 1

    pc.send(f"CONNECT {PC_CALL} {PHONE_CALL}")
    a = pc.wait_line("CONNECTED", CONNECT_TIMEOUT)
    b = phone.wait_line("CONNECTED", 15)
    if a is None or b is None:
        log(f"FAIL: not connected (PC: {a}, phone: {b})")
        return 1

    to_phone, to_pc = payload("PC->PHONE"), payload("PHONE->PC")
    log("PC data > 256 bytes")
    pc.data.sendall(to_phone)
    log("PHONE data > 256 bytes")
    phone.data.sendall(to_pc)

    got_phone = phone.wait_bytes(NBYTES, TRANSFER_TIMEOUT)
    got_pc = pc.wait_bytes(NBYTES, TRANSFER_TIMEOUT)
    ok_phone = got_phone == to_phone
    ok_pc = got_pc == to_pc
    log(f"RESULT PC->PHONE: {len(got_phone)} bytes, exact={ok_phone}")
    log(f"RESULT PHONE->PC: {len(got_pc)} bytes, exact={ok_pc}")

    pc.send("DISCONNECT")
    d1 = pc.wait_line("DISCONNECTED", DISCONNECT_TIMEOUT)
    d2 = phone.wait_line("DISCONNECTED", 15)
    log(f"RESULT disconnect: PC={d1 is not None} PHONE={d2 is not None}")

    passed = ok_phone and ok_pc and d1 is not None and d2 is not None
    log("PASS" if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        rc = main()
    except Exception as e:  # report, do not hide
        log(f"FAIL: exception {e!r}")
        rc = 2
    logf.close()
    sys.exit(rc)
