"""Phone Mercury answers calls as VA3OZO and logs everything, for testing a
caller such as Winlink Express. Reaches the phone's Mercury through
`adb forward tcp:9300 tcp:8400` and `tcp:9301 tcp:8401` (Mercury Modem app ports).
Ends after DISCONNECTED or after the time limit.

Usage: python listen_test.py <logfile> [seconds]
"""
import socket
import sys
import threading
import time

ADDR = ("127.0.0.1", 9300)
CALL = "VA3OZO"
LIMIT = int(sys.argv[2]) if len(sys.argv) > 2 else 300

t0 = time.time()
logf = open(sys.argv[1], "w", encoding="utf-8")
done = threading.Event()


def log(msg):
    line = f"{time.time() - t0:7.2f}s {msg}"
    print(line, flush=True)
    logf.write(line + "\n")
    logf.flush()


def read_ctl(sock):
    buf = b""
    while not done.is_set():
        try:
            chunk = sock.recv(4096)
        except OSError:
            break
        if not chunk:
            break
        buf += chunk
        while b"\r" in buf:
            line, buf = buf.split(b"\r", 1)
            text = line.decode("ascii", "replace").strip()
            if text and not text.startswith("BUFFER"):
                log(f"PHONE < {text}")
            if text.startswith("DISCONNECTED"):
                done.set()


def read_data(sock):
    total = 0
    while not done.is_set():
        try:
            chunk = sock.recv(4096)
        except OSError:
            break
        if not chunk:
            break
        total += len(chunk)
        log(f"PHONE data +{len(chunk)} bytes (total {total}): {chunk[:80]!r}")


ctl = socket.create_connection(ADDR, timeout=10)
data = socket.create_connection((ADDR[0], ADDR[1] + 1), timeout=10)
ctl.settimeout(None)
data.settimeout(None)
threading.Thread(target=read_ctl, args=(ctl,), daemon=True).start()
threading.Thread(target=read_data, args=(data,), daemon=True).start()
for cmd in (f"MYCALL {CALL}", "LISTEN ON"):
    log(f"PHONE > {cmd}")
    ctl.sendall(cmd.encode("ascii") + b"\r")
    time.sleep(0.3)
log(f"READY: phone answers as {CALL} for up to {LIMIT} s")
done.wait(LIMIT)
log("END: " + ("DISCONNECTED seen" if done.is_set() else "time limit"))
ctl.close()
data.close()
logf.close()
