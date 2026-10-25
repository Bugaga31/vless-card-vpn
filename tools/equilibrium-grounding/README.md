# Equilibrium grounding research — not an APK feature

This is a local, read-only experiment for a **pretrained** Qwen3-4B-Instruct-2507 model.
It is not weight training, a human mind, an autonomous VPN controller, or an Android runtime.
No model, source books, user manuscript, raw answers, secrets or datasets are committed here.

## Change from the earlier research prototype

Instead of trusting fluent generated paraphrases, the model selects complete verbatim
sentences. A deterministic gate verifies JSON shape, source digest, source ID, offsets,
quote membership, sentence boundaries and completion. Whitespace-only line-wrap differences map back to the original source slice; words and negations cannot be rewritten. It rejects unknown fields (including
actions), invented names/relationships, edits to quotations and many dropped-negation cuts.
A source instruction is kept as **data**. No quoted command is executed.

**Valid quotation ≠ relevant answer ≠ true fact.** The model can still select an irrelevant
sentence, omit a necessary qualification, or abstain despite available evidence. Human
semantic review remains required. Sentence-boundary heuristics are conservative and imperfect
(abbreviations, dialogue, multiline sentences). These tests are not an injection-proofness
or general reasoning certification. Outputs remain JSON data, not executable Markdown/HTML.

Retrieval selects at most two paragraph-based passages rather than padding context with unrelated neighbours. With operator-supplied `required_topics`, it prioritizes missing lexical topics before redundant passages. It is lexical with bounded prefix matching and explicitly supplied aliases; it is not multilingual semantic search.
Generation and inference remain local. This tool itself has no browsing, training or execution API.
A future network reader must separately implement opt-in, address/redirect/size controls and privacy.

## Run

Contract tests, Python standard library only:

```sh
python -m unittest discover -s tools/equilibrium-grounding/tests -v
```

The optional evaluator requires `llama-cpp-python==0.3.16` and the pinned GGUF:

- Repository: `unsloth/Qwen3-4B-Instruct-2507-GGUF`
- Revision: `a06e946bb6b655725eafa393f4a9745d460374c9`
- File: `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
- SHA-256: `3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597`

Model license and redistribution terms must be checked before any Android delivery.
Do not bundle this 2.5 GB file into a release merely because a desktop test ran.

```sh
python tools/equilibrium-grounding/evaluate.py --model /local/model.gguf \
  --cases /local/cases.json --output /local/results.json
```

Each local case has `id`, `source_id`, `title`, `source_path`, `question`, and optional
`aliases`, and optional `required_topics` (for example `{"telegram":["Telegram","Телеграм"],"youtube":["YouTube","Ютуб"]}`). Topic names/aliases are operator configuration, not model-generated facts. Local paths are operator-selected; this CLI is not a remotely exposed file API.
Keep manuscripts and evaluation outputs outside public repository paths.

## Release gates still missing

- Successful semantic evaluation, including a separate frozen holdout.
- Actual Android runtime integration, model license, opt-in model download and hash checking.
- Device RAM, latency, battery, cancellation, offline/error fallback and lifecycle testing.
- No automatic service PASS or VPN action based on generated text.
- Release CI, signatures and emulator checks for the final integrated SHA.

Until these exist: **not release ready**. Existing APK behavior is unchanged.

## v12: avoid silently dropping a requested service

- Explicit lexical topic coverage diversifies retrieval within the existing two-passage limit.
- A quotation response missing a required topic is rejected, not displayed as complete.
- Missing retrieval coverage (including after context trimming) skips generation and returns
  `insufficient`; it does not expand context without limit or fabricate a verdict.
- Every case records `generation_performed` and retrieval coverage. The no-evidence path uses
  the same provenance/action/training flags as the normal validator.
- Invalid evidence shapes, unhashable IDs and attempts to mark source instructions trusted
  fail with `Rejected` rather than an incidental exception or a permissive interpretation.
- Short lexical tokens require equality: Russian `тор` must not match `торговля`.

**Topic presence is not entailment.** Mentioning Telegram and YouTube does not prove either
service works, that an answer includes all qualifications, or that conflicting evidence was
resolved correctly. This is an optional, bounded omission guard, not a semantic verifier.
Required topics must be configured explicitly; arbitrary multi-part questions are not
reliably decomposed automatically. Three separate topics may not fit two passages: abstain.

71 standard-library tests pass (47 retained, 24 new). Four evaluator tests use an explicit
fake model, not real inference. No new model calls, weight training, internet access,
Android integration or release were performed in this iteration. See `RESULTS-v12.md`.
