"""Watson URL-only research baseline. Never fetches URLs from the dataset."""
import os
os.environ["OPENBLAS_NUM_THREADS"] = "2"
import csv, hashlib, ipaddress, json, math, re, shutil
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit
import numpy as np
import pandas as pd

ROOT = Path(__file__).parent
RAW = Path("/data/raw")
OUT = Path("/data/analysis")
SEED = "watson-url-v0-domain-split"

class Suffixes:
    def __init__(self, text):
        self.normal, self.wild, self.exceptions = set(), set(), set()
        for row in text.splitlines():
            row = row.strip()
            if not row or row.startswith("//"):
                continue
            if row.startswith("!"):
                self.exceptions.add(row[1:].encode("idna").decode())
            elif row.startswith("*."):
                self.wild.add(row[2:].encode("idna").decode())
            else:
                self.normal.add(row.encode("idna").decode())

    def group(self, host):
        try:
            return str(ipaddress.ip_address(host))
        except ValueError:
            pass
        parts = host.split(".")
        suffix_len = 1
        for i in range(len(parts)):
            tail = ".".join(parts[i:])
            if tail in self.exceptions:
                suffix_len = len(parts) - i - 1
                break
            if tail in self.normal:
                suffix_len = max(suffix_len, len(parts) - i)
            if i > 0 and tail in self.wild:
                suffix_len = max(suffix_len, len(parts) - i + 1)
        return ".".join(parts[-min(len(parts), suffix_len + 1):])

FEATURES = [
    "log_url_length", "log_host_length", "log_path_length", "log_query_length",
    "host_dot_count", "host_hyphen_count", "url_digit_fraction",
    "host_digit_fraction", "percent_count", "at_count", "question_count",
    "equals_count", "ampersand_count", "path_depth", "https_scheme",
    "ip_host", "punycode_host", "nonascii_url", "explicit_port",
    "host_entropy", "longest_host_label", "credential_terms", "download_terms",
]

def parse(url):
    try:
        p = urlsplit(url if "://" in url else "http://" + url)
        host = (p.hostname or "").lower().rstrip(".").encode("idna").decode()
        if not host or p.scheme.lower() not in ("http", "https"):
            return None
        port = p.port
    except (ValueError, UnicodeError):
        return None
    try:
        ipaddress.ip_address(host)
        ip = 1
    except ValueError:
        ip = 0
    counts = Counter(host)
    ent = -sum((n / len(host)) * math.log2(n / len(host)) for n in counts.values())
    low = url.lower()
    x = [
        math.log1p(len(url)), math.log1p(len(host)), math.log1p(len(p.path)),
        math.log1p(len(p.query)), min(host.count("."), 20),
        min(host.count("-"), 20), sum(c.isdigit() for c in url) / max(1, len(url)),
        sum(c.isdigit() for c in host) / len(host),
        min(url.count("%"), 30), min(url.count("@"), 10), min(url.count("?"), 10),
        min(url.count("="), 30), min(url.count("&"), 30), min(p.path.count("/"), 30),
        int(p.scheme.lower() == "https"), ip, int("xn--" in host),
        int(any(ord(c) > 127 for c in url)), int(port is not None),
        ent, min(max(map(len, host.split("."))), 100),
        sum(low.count(s) for s in ("login", "verify", "account", "secure", "password", "signin")),
        sum(low.count(s) for s in (".apk", ".exe", "download", "update")),
    ]
    return host, x

def sigmoid(z):
    return 1 / (1 + np.exp(-np.clip(z, -35, 35)))

def metrics(y, scores, threshold):
    pred = scores >= threshold
    tp = int(np.sum(pred & (y == 1)))
    fp = int(np.sum(pred & (y == 0)))
    tn = int(np.sum(~pred & (y == 0)))
    fn = int(np.sum(~pred & (y == 1)))
    return dict(n=len(y), phishing=int(y.sum()), legitimate=int((y == 0).sum()),
                tp=tp, fp=fp, tn=tn, fn=fn, recall=tp / max(1, tp + fn),
                precision=tp / max(1, tp + fp), fpr=fp / max(1, fp + tn))

def main():
    psl = Suffixes((RAW / "public_suffix_list.dat").read_text())
    df = pd.read_csv(RAW / "phiusiil.csv", usecols=["URL", "label"])
    original_n = len(df)
    assert set(df["label"]) == {0, 1}
    missing = int(df["URL"].isna().sum())
    df = df.dropna(subset=["URL"])
    conflict = df.groupby("URL")["label"].nunique()
    bad = set(conflict[conflict > 1].index)
    conflicts_removed = int(df["URL"].isin(bad).sum())
    df = df[~df["URL"].isin(bad)]
    duplicates = int(df.duplicated("URL").sum())
    df = df.drop_duplicates("URL")
    xs, ys, groups, splits = [], [], [], []
    invalid = 0
    for url, label in df.itertuples(index=False, name=None):
        parsed = parse(url)
        if parsed is None:
            invalid += 1
            continue
        host, x = parsed
        group = psl.group(host)
        v = int.from_bytes(hashlib.sha256((SEED + group).encode()).digest()[:8], "big") % 100
        split = "train" if v < 70 else "validation" if v < 85 else "test"
        xs.append(x)
        ys.append(1 - int(label))  # Explicit reversal: phishing = 1.
        groups.append(group)
        splits.append(split)
    X, y, split = np.asarray(xs), np.asarray(ys), np.asarray(splits)
    tr, va, te = (split == s for s in ("train", "validation", "test"))
    sets = [set(np.array(groups)[m]) for m in (tr, va, te)]
    assert not (sets[0] & sets[1] or sets[0] & sets[2] or sets[1] & sets[2])
    mean, scale = X[tr].mean(axis=0), X[tr].std(axis=0)
    scale[scale < 1e-8] = 1
    Z = np.column_stack(((X - mean) / scale, np.ones(len(X))))
    A, b = Z[tr], y[tr]
    w = np.zeros(A.shape[1])
    m, v = w.copy(), w.copy()
    # Fixed training schedule; no tuning against test or validation.
    for epoch in range(1, 401):
        grad = A.T @ (sigmoid(A @ w) - b) / len(A)
        grad[:-1] += .001 * w[:-1]
        m = .9 * m + .1 * grad
        v = .999 * v + .001 * grad * grad
        w -= .03 * (m / (1 - .9 ** epoch)) / (np.sqrt(v / (1 - .999 ** epoch)) + 1e-8)
    validation_scores = sigmoid(Z[va] @ w)
    # Low-FPR target selected only on validation; ties resolved conservatively.
    benign = np.sort(validation_scores[y[va] == 0])
    rank = int(math.ceil(.99 * len(benign))) - 1
    threshold = float(np.nextafter(benign[max(0, rank)], np.inf))
    test_scores = sigmoid(Z[te] @ w)
    test = metrics(y[te], test_scores, threshold)
    # Independent check via Python iteration instead of vectorized aggregates.
    cross = Counter((int(a), int(s >= threshold)) for a, s in zip(y[te], test_scores))
    assert (cross[1, 1], cross[0, 1], cross[0, 0], cross[1, 0]) == (
        test["tp"], test["fp"], test["tn"], test["fn"])
    assert sum(test[k] for k in ("tp", "fp", "tn", "fn")) == test["n"]
    model = dict(format="watson-url-logistic-v0", production_ready=False,
                 features=FEATURES, mean=mean.tolist(), scale=scale.tolist(),
                 weights=w[:-1].tolist(), intercept=float(w[-1]),
                 threshold=threshold, positive_class="dataset-labelled phishing",
                 output="uncalibrated score, NOT probability of compromise",
                 allowed_action="research warning only; never automatic block",
                 training="400 fixed full-batch Adam steps; L2=.001; lr=.03")
    (ROOT / "model.json").write_text(json.dumps(model, indent=2))
    np.savez_compressed(OUT / "watson-derived-features.npz", X=X, y=y, split=split)
    evidence = dict(
        source="https://archive.ics.uci.edu/dataset/967/phiusiil+phishing+url+dataset",
        attribution="Prasad, A. & Chandra, S. (2024), PhiUSIIL, UCI, CC BY 4.0",
        csv_sha256=hashlib.sha256((RAW / "phiusiil.csv").read_bytes()).hexdigest(),
        psl_sha256=hashlib.sha256((RAW / "public_suffix_list.dat").read_bytes()).hexdigest(),
        model_sha256=hashlib.sha256((ROOT / "model.json").read_bytes()).hexdigest(),
        original_rows=original_n, missing_urls=missing, conflicting_rows=conflicts_removed,
        duplicate_urls_removed=duplicates, invalid_urls_removed=invalid,
        retained_rows=len(y), retained_label_counts=dict(phishing=int(y.sum()), legitimate=int((y == 0).sum())),
        split_method="SHA256 seeded registrable-domain bucket 70/15/15, PSL ICANN+PRIVATE; no group overlap",
        seed=SEED, groups=[len(s) for s in sets],
        train_rows=int(tr.sum()), validation_rows=int(va.sum()), test_rows=int(te.sum()),
        validation=metrics(y[va], validation_scores, threshold), test=test,
        threshold=threshold, feature_count=len(FEATURES), network_sample_requests=0,
        comparison_plan="Prose: one held-out test result; no temporal or model comparison claimed.",
        limitations=[
            "Historical 2024 single-source labels, not verified active threats or 2026 generalization.",
            "Domain separation is not separation by campaign, provider, brand or time.",
            "No current Russian-language, short-link, redirect-chain, QR, APK, message or call validation.",
            "URL lexical features only; HTTPS and lexical patterns are not evidence of safety or malice.",
            "Score not calibrated; low score means unknown, never safe.",
            "No automatic blocking or production deployment; independent external test required.",
            "VPN metadata normally does not reveal full encrypted HTTPS URL path/query.",
        ])
    (OUT / "watson-evidence.json").write_text(json.dumps(evidence, indent=2))
    (ROOT / "evidence.json").write_text(json.dumps(evidence, indent=2))
    shutil.copyfile(RAW / "public_suffix_list.dat", ROOT / "public_suffix_list.dat")
    print(json.dumps({k:evidence[k] for k in ("original_rows", "retained_rows", "train_rows", "validation_rows", "test_rows", "test", "threshold", "feature_count")}))

if __name__ == "__main__":
    main()