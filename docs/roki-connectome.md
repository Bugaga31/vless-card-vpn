# ROKI: restricted FlyWire prototype

## Provenance

Source: Betzel RF, Puxeddu MG, Seguin C, Misic B (2026), *Cascades and convergence: Dynamic signal flow in a synapse-level brain network*, https://journals.plos.org/complexsystems/article?id=10.1371/journal.pcsy.0000091 . Dataset derivatives: https://zenodo.org/records/18555170 , CC BY 4.0, based on FlyWire FAFB v783. This is not MaleCNS, DOOMFLY code, Gemini or a complete brain.

The immutable input record and checksums are in `tools/roki/model.json`. `connectome.mat` contains W.TOT; paper S4 defines A_ij as synapses from i to j. MATLAB annotation indices are one-based, converted to zero-based rows. Seed 216 selects 384 Kenyon_Cell, 32 DAN, all 96 MBON and all 4 MBIN rows; the retained induced subgraph has 516 neurons and 11517 directed connections. Embedded raw synapse counts and row mapping are SHA256 checked before normalization. Graph SHA256: b3f94ee800f9555cb02f7c397c183af7e24e11ac5fb735e871849de997dd5b84. Original data authors retain their rights. Changes: subset selection, normalization, engineered encoding, tanh propagation, pooling and trained output.

## What is and is not biological

Real data determine retained adjacency and synapse-count weights. This reduced circuit omits most neurons and external inputs. Neurotransmitter signs, spiking timing, plasticity at biological synapses, physiology, emotions and a complete nervous system are NOT reproduced. All-positive normalized weights are a deliberate simplification. Five normalized aggregate network-history features drive an arbitrary deterministic projection; three synchronous tanh steps are pooled into sixteen channels. The output is a trained logistic readout, not a natural fly decision or antivirus.

## Training and safeguards

`tools/roki/train.py` records and reproduces synthetic initialization (seed 1216, train/heldout split before fitting). Synthetic heldout loss is only a software check, not measured VPN performance. No phone, private endpoints, real provider results, messages or calls entered this training. After eight completed non-cancelled observations for the exact network/profile/latency/test URL, online readout updates may reorder the first 64 candidates. Context count <=24; feature cache <=128. Remaining candidates are retained. Unknown outcomes do not train; a complete failure remains a negative label. Evidence before the outcome is used as input. Training and inference are in-memory; restart resets online updates. No cloud/persistent observation logs.

Admission remains the existing current-profile HTTPS/service checks, exact network and pin checks. ROKI cannot approve a failed/unknown gate, alter keys/SNI, change thermal/concurrency policies or replace a working tunnel. Runtime model errors fall back to original ordering; no claim of a speed, battery or security gain. Performance and benefit on a real phone must be measured before stronger autonomy or expansion to the whole graph.

## Related research and security audit

DOOMFLY https://github.com/nftechie/doomfly explicitly reports failed learning gates; its artificial adapters and plasticity do not prove a generic brain can solve a new task. English video transcript examined: https://www.youtube.com/watch?v=WRR3KMikWe4 . Russian video https://www.youtube.com/watch?v=G070Yvd_OkY did not expose usable content; full viewing is not claimed.

Current configuration code was inspected against Xray REALITY https://xtls.github.io/en/config/transports/reality.html and Tor bridge documentation https://support.torproject.org/tor-vpn/circumvention/using-bridges/ . Existing server keys, SNI, pins and ML-DSA verification fields are preserved. obfs4/WebTunnel are not equivalent to arbitrary direct TLS or a guarantee of ISP invisibility. This release does not add random fingerprints, disable certificate validation or claim a real-provider DPI test. A server-side target/protocol change requires compatible server configuration, not a client-only guess.
