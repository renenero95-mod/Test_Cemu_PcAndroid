#!/usr/bin/env python3
"""Independent re-implementation (numpy, float64) of the MLP forward pass and ONE training step.
Reads the case printed by `math_test --dump` and compares it with what the C++ code produced.
Run: math_test --dump | python3 tests/neural/numpy_reference.py"""
import sys
import numpy as np

def parse(lines):
    d = {}
    for ln in lines:
        p = ln.split()
        if not p: continue
        d[(p[0], p[1]) if p[0] in ("BEFORE", "AFTER") else p[0]] = p[1:] if p[0] not in ("BEFORE", "AFTER") else p[2:]
    return d

def main():
    d = parse(sys.stdin.read().splitlines())
    dims = [int(v) for v in d["DIMS"]]
    L = len(dims) - 1
    W = [np.array(d[("BEFORE", f"W{l}")], dtype=np.float64).reshape(dims[l + 1], dims[l]) for l in range(L)]
    B = [np.array(d[("BEFORE", f"B{l}")], dtype=np.float64) for l in range(L)]
    x = np.array(d["INPUT"], dtype=np.float64)
    t = float(d["TARGET"][0])
    lr, delta, clip, wd = [float(v) for v in d["HYPER"]]

    def forward(W, B, x):
        a = [np.clip(x, -8.0, 8.0)]
        for l in range(L):
            z = W[l] @ a[-1] + B[l]
            a.append(np.tanh(z) if l < L - 1 else z)
        return a

    a = forward(W, B, x)
    out = a[-1][0]
    ok = True
    print(f"forward: C++ {float(d['FORWARD'][0]):.6f}  numpy {out:.6f}")
    ok &= abs(out - float(d["FORWARD"][0])) < 1e-5
    e = out - t
    g = np.array([np.clip(e, -delta, delta)])        # Huber derivative
    gW, gB = [None] * L, [None] * L
    for l in range(L - 1, -1, -1):
        gW[l] = np.outer(g, a[l]); gB[l] = g
        if l > 0:
            g = (W[l].T @ g) * (1.0 - a[l] ** 2)
    norm = np.sqrt(sum((x_ ** 2).sum() for x_ in gW) + sum((x_ ** 2).sum() for x_ in gB))
    scale = clip / norm if (clip > 0 and norm > clip) else 1.0
    print(f"gradient norm {norm:.4f} (clip {clip}) -> scale {scale:.4f}")
    worst = 0.0
    for l in range(L):
        newW = W[l] - lr * (gW[l] * scale + wd * W[l])
        newB = B[l] - lr * gB[l] * scale
        cW = np.array(d[("AFTER", f"W{l}")], dtype=np.float64).reshape(dims[l + 1], dims[l])
        cB = np.array(d[("AFTER", f"B{l}")], dtype=np.float64)
        worst = max(worst, np.abs(newW - cW).max(), np.abs(newB - cB).max())
    print(f"after one step: max |C++ - numpy| over all weights = {worst:.2e}")
    ok &= worst < 1e-5
    print("NUMPY REFERENCE:", "MATCH" if ok else "MISMATCH")
    sys.exit(0 if ok else 1)

main()
