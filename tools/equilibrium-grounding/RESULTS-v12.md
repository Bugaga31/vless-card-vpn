# Equilibrium v12 — bounded omission guard, not model fine-tuning

Base: `9ced3a39d19674f2e96807274b7aadeb69407f6d` (v11 research branch).
Only `tools/equilibrium-grounding/**` changed. Published APK v1.2.54 is untouched.

## Problem and implemented change

A high-scoring Telegram paragraph and a second redundant Telegram paragraph could occupy
both evidence slots while a lower-scoring YouTube paragraph was excluded. Even valid
source quotations could then omit a requested service. Required lexical topics now
prioritize uncovered topics; the quotation gate rejects answers omitting those topics.
Missing source/context coverage produces an explicit abstention without generation.

Short tokens no longer match arbitrary longer prefixes (e.g. тор → торговля).
Malformed evidence and trusted-instruction flags are rejected explicitly.

## Executed verification

`python -m unittest discover -s tools/equilibrium-grounding/tests -v`

- 71 tests; 0 failures; 0 errors; 0 skipped.
- 47 previous regressions retained unchanged; 20 new topic/evidence regressions.
- 4 evaluator-control-flow tests use a FAKE model: no inference or trained model.
- Checks include missing YouTube, explicit UNKNOWN text preserved, invented PASS rejected,
  context-trim abstention, aliases, stable order, strict evidence shape, no extra passages,
  source instructions remaining untrusted and topic presence not becoming semantic proof.
- `py_compile` and `git diff --check` passed.

## Not verified or implemented

No GGUF was present in the current workspace, and llama-cpp-python was not installed for
this iteration. No new inference, books-based weight training, frozen semantic holdout,
Android model runtime, physical-phone or operator tests were performed. Earlier v11 model
calls remain earlier results, not v12 results. The model weights have not changed.

Lexical coverage cannot detect every omission, contradiction, stale statement, negation
scope or irrelevant quotation. This is a development regression suite, not an independent
accuracy benchmark. Source text remains untrusted data; no browse or action API is added.
An integrated AI release is NOT ready. No new APK/tag/GitHub Release is created.
