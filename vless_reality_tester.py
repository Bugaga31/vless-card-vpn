#!/usr/bin/env python3
"""
vless_reality_tester.py

Clean, standalone Python 3 script demonstrating auto-testing of VLESS Reality configs.
Production-quality PoC: minimal deps (stdlib + optional requests), robust error handling,
logging, CLI via argparse, realistic 2026 Russia-oriented settings (3s timeout, 2 retries,
prefer IPv4, Yandex-style SNI common in RU).

Usage examples:
  python vless_reality_tester.py
  python vless_reality_tester.py --vless "vless://..." --vless "vless://..."
  python vless_reality_tester.py --sub "https://example.com/sub.txt" --timeout 2.5

Input formats supported:
- vless:// URIs (CLI or subscription)
- Internal dicts with keys: address, port, uuid, flow, security, pbk, sid, sni, fp, remark

Tests performed per config:
1. TCP connect (IPv4 preferred) + measure connect time.
2. TLS handshake (SNI set correctly, uTLS-fp simulated via stdlib note; verify disabled
   because Reality uses fake certs / fronting).
3. No full proxying / HEAD through tunnel (pre-connect reality check only: reachability
   + TLS to the masked SNI). If handshake succeeds the Reality front is responsive.

Output: sorted table by latency (alive first), with latency_ms, status (alive/dead/timeout),
timestamp. Fail fast on individual attempts.

Android porting notes (top-level comments):
- Schedule with WorkManager (one-time or periodic) + Constraints (network, battery).
- Parse vless same way (kotlin/java URI or custom parser).
- Network: OkHttp + custom SSLSocketFactory for SNI. For realistic uTLS fingerprint use
  okhttp3-tls or integrate with sing-box / v2rayNG test API via AIDL / intent (preferred
  for production - call sing-box's "test outbound" or "ping" endpoint).
- Reality handshake test: use OkHttp with .sslSocketFactory that forces SNI + fp-like
  ClientHello (or delegate to sing-box core for accurate Reality validation).
- IPv4 preference: use NetworkCapabilities or OkHttp custom DNS preferring v4.
- Store results in Room DB, expose via LiveData. Use exponential backoff for retries.
- Logging: Timber or android.util.Log. No root needed for basic tests.
- Full end-to-end (through proxy) would require VPNService + tun2socks or sing-box
  integration, not simple socket.
- Keep this script's logic as reference for the "test outbound" worker.

This script is self-contained. Hardcoded realistic (fake) examples use common RU SNIs
(www.yandex.ru, www.google.com etc) for PoC. Actual VLESS Reality servers will pass
when their address:port + SNI is reachable and TLS handshake completes.
"""

import argparse
import base64
import datetime
import logging
import socket
import ssl
import sys
import time
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

# Optional dependency for subscription fetching
try:
    import requests  # type: ignore
except ImportError:
    requests = None  # type: ignore

# Configure module logger (production style)
logger = logging.getLogger("vless_reality_tester")
handler = logging.StreamHandler(sys.stdout)
handler.setFormatter(logging.Formatter(
    "%(asctime)s [%(levelname)s] %(message)s", datefmt="%H:%M:%S"
))
logger.addHandler(handler)
logger.setLevel(logging.INFO)


def parse_vless_uri(uri: str) -> Optional[Dict[str, Any]]:
    """Parse a vless:// URI into a normalized config dict.
    Supports all common Reality fields. Returns None on malformed input.
    """
    if not uri or not uri.startswith("vless://"):
        return None

    try:
        # Strip scheme
        raw = uri[8:]

        # Extract remark (fragment)
        remark = ""
        if "#" in raw:
            raw, fragment = raw.split("#", 1)
            remark = urllib.parse.unquote(fragment).strip()

        # Split userinfo @ hostinfo
        if "@" not in raw:
            return None
        userinfo, hostinfo = raw.split("@", 1)

        uuid = userinfo.strip()

        # Split host:port?query
        query = ""
        if "?" in hostinfo:
            hostinfo, query = hostinfo.split("?", 1)

        # Host and port (last : for IPv6 safety but we force IPv4 later)
        if ":" in hostinfo:
            # Take rightmost colon for port
            address, port_str = hostinfo.rsplit(":", 1)
            try:
                port = int(port_str)
            except ValueError:
                port = 443
        else:
            address = hostinfo.strip()
            port = 443

        address = address.strip("[]").strip()  # remove IPv6 brackets if any

        # Parse query params (Reality fields)
        params: Dict[str, List[str]] = urllib.parse.parse_qs(query) if query else {}

        def first(key: str, default: str = "") -> str:
            vals = params.get(key, [])
            return vals[0] if vals else default

        config: Dict[str, Any] = {
            "address": address,
            "port": port,
            "uuid": uuid,
            "flow": first("flow", "xtls-rprx-vision"),
            "security": first("security", "reality"),
            "pbk": first("pbk"),
            "sid": first("sid"),
            "sni": first("sni") or address,
            "fp": first("fp", "chrome"),
            "remark": remark or address,
        }
        return config
    except Exception as exc:
        logger.debug("Failed to parse vless URI: %s (%s)", uri[:60], exc)
        return None


def fetch_subscription(url: str, timeout: float = 10.0) -> List[str]:
    """Fetch subscription, attempt base64 decode, return list of vless:// lines.
    Gracefully handles plain text subs too.
    """
    if requests is None:
        logger.error("requests not installed. Cannot fetch subscription. "
                     "Install with: pip install requests")
        return []

    try:
        logger.info("Fetching subscription: %s", url)
        resp = requests.get(
            url,
            timeout=timeout,
            headers={"User-Agent": "necli-vless-tester/2026"},
            allow_redirects=True,
        )
        resp.raise_for_status()
        content = resp.text.strip()

        # Try base64 decode (common for subs)
        decoded = content
        try:
            # Many subs are pure base64 of the lines
            b64_candidate = content.replace("\n", "").replace("\r", "").strip()
            if len(b64_candidate) > 20 and not b64_candidate.startswith("vless://"):
                decoded_bytes = base64.b64decode(b64_candidate + "==")  # padding tolerance
                decoded = decoded_bytes.decode("utf-8", errors="ignore")
        except Exception:
            pass  # keep original content

        lines = []
        for line in decoded.splitlines():
            line = line.strip()
            if line.startswith("vless://"):
                lines.append(line)
        logger.info("Subscription yielded %d vless configs", len(lines))
        return lines
    except Exception as exc:
        logger.error("Subscription fetch failed: %s", exc)
        return []


def test_single_config(
    config: Dict[str, Any],
    timeout: float = 3.0,
    retries: int = 2,
) -> Dict[str, Any]:
    """Perform TCP + TLS Reality reachability test for one config.

    - Strictly IPv4 (AF_INET) as per 2026 RU best-practice (avoid CGNAT v6 issues).
    - Measures full connect + TLS handshake latency.
    - Uses SNI exactly as provided (critical for Reality masking).
    - stdlib ssl: cannot emulate real uTLS fingerprints (chrome, firefox, etc.).
      In real clients use sing-box / v2ray-core / utls. Here we only prove SNI path.
    - Returns rich result dict. Does NOT attempt actual VLESS auth or proxying.
    """
    remark = config.get("remark", config.get("address", "unknown"))
    address = config["address"]
    port = int(config.get("port", 443))
    sni = config.get("sni", address)
    fp = config.get("fp", "chrome")

    result: Dict[str, Any] = {
        "remark": remark,
        "address": address,
        "port": port,
        "sni": sni,
        "fp": fp,
        "latency_ms": None,
        "status": "dead",
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "error": None,
    }

    logger.debug("Testing %s (%s:%d) sni=%s fp=%s", remark, address, port, sni, fp)

    last_exc: Optional[Exception] = None

    for attempt in range(1, retries + 1):
        sock = None
        ssock = None
        try:
            # 1. DNS + TCP connect (IPv4 only)
            t0 = time.perf_counter()
            try:
                # Force IPv4
                addrinfo = socket.getaddrinfo(
                    address, port, family=socket.AF_INET,
                    type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP
                )
                if not addrinfo:
                    raise socket.gaierror("No IPv4 address")
                target_ip = addrinfo[0][4][0]
            except socket.gaierror as ge:
                # Fallback to the literal if it's already an IP
                target_ip = address
                if not any(c.isdigit() or c == '.' for c in target_ip):
                    raise ge

            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)

            conn_start = time.perf_counter()
            sock.connect((target_ip, port))
            connect_ms = (time.perf_counter() - conn_start) * 1000

            # 2. TLS handshake with correct SNI (Reality fronting simulation)
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            # NOTE: No way to force specific uTLS fingerprint with stdlib ssl.
            # Reality servers often accept many fingerprints; the key is correct SNI.
            # Real production: use tls_client, utls (golang), or sing-box test API.

            tls_start = time.perf_counter()
            ssock = ctx.wrap_socket(sock, server_hostname=sni)
            tls_ms = (time.perf_counter() - tls_start) * 1000

            total_ms = (time.perf_counter() - t0) * 1000

            # Success: port open + TLS handshake to SNI completed.
            # For Reality this is the primary pre-flight signal.
            result["latency_ms"] = round(total_ms, 1)
            result["status"] = "alive"
            result["error"] = None

            logger.info(
                "ALIVE  %s  %s:%d  sni=%s  %.1fms (connect %.1f + tls %.1f)",
                remark, address, port, sni, total_ms, connect_ms, tls_ms
            )
            return result

        except socket.timeout:
            last_exc = TimeoutError("connect or tls timeout")
            result["status"] = "timeout"
            result["error"] = "timeout"
            logger.warning("TIMEOUT attempt %d/%d for %s", attempt, retries, remark)
        except ConnectionRefusedError:
            last_exc = ConnectionRefusedError("connection refused")
            result["status"] = "dead"
            result["error"] = "refused"
            logger.warning("REFUSED attempt %d/%d for %s", attempt, retries, remark)
        except ssl.SSLError as se:
            last_exc = se
            result["status"] = "dead"
            result["error"] = f"tls:{str(se)[:50]}"
            logger.warning("TLS-ERR attempt %d/%d for %s: %s", attempt, retries, remark, se)
        except Exception as exc:
            last_exc = exc
            result["status"] = "dead"
            result["error"] = str(exc)[:80]
            logger.debug("FAIL attempt %d/%d for %s: %s", attempt, retries, remark, exc)
        finally:
            # Clean shutdown
            for s in (ssock, sock):
                if s is not None:
                    try:
                        s.close()
                    except Exception:
                        pass

        if attempt < retries:
            time.sleep(0.25)  # small backoff between retries

    # All attempts exhausted
    if result["error"] is None:
        result["error"] = str(last_exc)[:80] if last_exc else "unknown"
    logger.info("DEAD   %s  %s:%d  %s", remark, address, port, result["error"])
    return result


def print_results_table(results: List[Dict[str, Any]]) -> None:
    """Pretty console table. Alive configs sorted by ascending latency.
    Dead/timeout placed after.
    """
    if not results:
        print("No configs to test.")
        return

    # Separate and sort
    alive = [r for r in results if r["status"] == "alive"]
    dead = [r for r in results if r["status"] != "alive"]

    alive_sorted = sorted(alive, key=lambda r: r["latency_ms"] or 999999.0)
    dead_sorted = sorted(dead, key=lambda r: (r["status"], r["remark"]))

    ordered = alive_sorted + dead_sorted

    # Header
    header = (
        f"{'#':<3} "
        f"{'Remark':<28} "
        f"{'Address':<18} "
        f"{'Port':>5} "
        f"{'SNI':<20} "
        f"{'Latency':>9} "
        f"{'Status':<8} "
        f"{'Timestamp':<20}"
    )
    print(header)
    print("-" * len(header))

    for idx, r in enumerate(ordered, 1):
        lat_str = f"{r['latency_ms']}ms" if r["latency_ms"] is not None else "-"
        ts_short = r["timestamp"][:19].replace("T", " ")
        print(
            f"{idx:<3} "
            f"{r['remark'][:27]:<28} "
            f"{r['address'][:17]:<18} "
            f"{r['port']:>5} "
            f"{r['sni'][:19]:<20} "
            f"{lat_str:>9} "
            f"{r['status']:<8} "
            f"{ts_short:<20}"
        )

    # Summary line
    alive_count = len(alive_sorted)
    avg_lat = (
        sum(r["latency_ms"] for r in alive_sorted) / alive_count
        if alive_count > 0 else 0
    )
    print("-" * len(header))
    print(
        f"Summary: {alive_count}/{len(results)} alive  |  "
        f"avg latency {avg_lat:.1f}ms  |  "
        f"tested at {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
    )


def load_hardcoded_examples() -> List[Dict[str, Any]]:
    """Return 3 realistic (but non-functional for security) example configs.
    All use yandex.ru / google SNI common in Russian VLESS Reality deployments.
    """
    uris = [
        # Example 1 - "working" style Yandex front (will likely be alive because TLS to yandex works)
        "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@93.158.134.3:443"
        "?security=reality&flow=xtls-rprx-vision"
        "&pbk=3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X3s1X"
        "&sid=0123456789abcdef&sni=www.yandex.ru&fp=chrome#RU-Yandex-Prod-01",

        # Example 2 - Another Yandex IP range (realistic)
        "vless://b2c3d4e5-f6a7-8901-bcde-f23456789012@5.255.255.70:443"
        "?security=reality&flow=xtls-rprx-vision"
        "&pbk=4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y4t2Y"
        "&sid=abcdef0123456789&sni=www.yandex.ru&fp=firefox#RU-Yandex-Prod-02",

        # Example 3 - Known dead / test IP (documentation range)
        "vless://c3d4e5f6-a7b8-9012-cdef-345678901234@198.51.100.42:443"
        "?security=reality&flow=xtls-rprx-vision"
        "&pbk=5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z5u3Z"
        "&sid=9876543210fedcba&sni=www.google.com&fp=chrome#Test-Dead-Example",
    ]
    configs: List[Dict[str, Any]] = []
    for uri in uris:
        parsed = parse_vless_uri(uri)
        if parsed:
            configs.append(parsed)
    return configs


def main() -> None:
    parser = argparse.ArgumentParser(
        description="VLESS Reality auto-tester (PoC 2026). "
                    "Tests TCP+TLS reachability + SNI handshake latency.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Examples:
  python vless_reality_tester.py
  python vless_reality_tester.py --vless 'vless://...' --vless 'vless://...'
  python vless_reality_tester.py --sub https://sub.example.com/base64.txt --timeout 2.0 --retries 1
""",
    )
    parser.add_argument(
        "--vless", action="append", metavar="URI",
        help="Add one or more vless:// URIs (can be repeated)"
    )
    parser.add_argument(
        "--sub", metavar="URL",
        help="Fetch subscription URL (base64 or plain text containing vless lines)"
    )
    parser.add_argument(
        "--timeout", type=float, default=3.0,
        help="Per-attempt socket timeout in seconds (default: 3.0)"
    )
    parser.add_argument(
        "--retries", type=int, default=2,
        help="Number of retries per config (default: 2)"
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true",
        help="Enable debug logging"
    )
    parser.add_argument(
        "--json", action="store_true",
        help="Output raw JSON results instead of table (for scripting)"
    )

    args = parser.parse_args()

    if args.verbose:
        logger.setLevel(logging.DEBUG)

    # Collect input configs (priority: CLI > sub > hardcoded PoC)
    raw_configs: List[Dict[str, Any]] = []

    if args.vless:
        for uri in args.vless:
            c = parse_vless_uri(uri)
            if c:
                raw_configs.append(c)
            else:
                logger.warning("Skipped invalid --vless: %s", uri[:70])

    if args.sub:
        uris = fetch_subscription(args.sub)
        for uri in uris:
            c = parse_vless_uri(uri)
            if c:
                raw_configs.append(c)

    if not raw_configs:
        logger.info("No --vless or --sub provided. Using 3 hardcoded realistic examples.")
        raw_configs = load_hardcoded_examples()

    if not raw_configs:
        logger.error("No valid configs loaded. Exiting.")
        sys.exit(1)

    logger.info("Loaded %d config(s). Timeout=%.1fs, retries=%d", len(raw_configs), args.timeout, args.retries)

    # Run tests
    results: List[Dict[str, Any]] = []
    for i, cfg in enumerate(raw_configs, 1):
        logger.debug("Processing config %d/%d: %s", i, len(raw_configs), cfg.get("remark"))
        res = test_single_config(cfg, timeout=args.timeout, retries=args.retries)
        results.append(res)

    # Output
    if args.json:
        import json
        print(json.dumps(results, indent=2, ensure_ascii=False))
    else:
        print_results_table(results)

    # Exit code semantics
    alive = sum(1 for r in results if r["status"] == "alive")
    if alive == 0:
        logger.warning("All configs dead/timeout.")
        sys.exit(2)
    elif alive < len(results):
        sys.exit(1)  # partial success
    # else: all good, implicit exit 0


if __name__ == "__main__":
    main()
