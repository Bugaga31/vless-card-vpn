# Equalizer language pilot — EXPERIMENTAL, NOT RELEASE-READY

This is a real CPU LoRA fine-tuning experiment, not a system-prompt-only claim.
Base: Qwen/Qwen2.5-0.5B-Instruct
Exact revision: 7ae557604adf67be50417f59c2c2f167def9a775
Base license: Apache-2.0 (included).

Run 1: 42 synthetic training dialogues, 42 optimizer steps; rejected after identity error.
Run 2: 90 original synthetic training dialogues, 90 optimizer steps,
540672 trainable parameters, 96 changed adapter tensors.
Frozen base weights were not selected by the optimizer. Adapter is saved in safetensors.

The eight validation examples had already been inspected after run1. They are NOT a
blind test, expert benchmark, or evidence of general intelligence. Six fresh prompts
were generated after reloading the saved adapter. The answers still contain technical
errors. See quality-review.json: production_approved=false.

The adapter is NOT integrated into an APK, does NOT control routes, and cannot set
PASS/FAIL. No consciousness, human-level reasoning, operator availability or Tor
bootstrap is claimed. No book ending, user traffic, private configuration or secret
was used for training. No external paid compute was started.

## What is included
- adapter/: trained LoRA weights, config, tokenizer; NOT the ~0.5B base model.
- train.py, prepare_data.py: run2 training and synthetic dataset preparation.
- train.jsonl, holdout.jsonl: exact training and inspected-validation examples.
- base-model.json, dataset-manifest.json, training-result.json: provenance and metrics.
- fresh-evaluation.json, quality-review.json: actual generated answers and rejection.
- requirements.freeze.txt and SHA256SUMS.txt.
- run1-result.json and run1-prepare_data.py: first iteration provenance.

## Reproduce (CPU, no API key)
Use an isolated Python environment. Install torch==2.8.0+cpu from the official CPU
wheel index https://download.pytorch.org/whl/cpu, then the remaining pinned packages
in requirements.freeze.txt. The original run used Python 3.13, two CPU threads and
approximately 4 GiB total machine RAM. Model download requires network access.
Create .venv in this directory; train.py records .venv/bin/pip freeze.
Run: .venv/bin/python train.py
Then: .venv/bin/python evaluate_fresh.py to reload and evaluate the saved adapter.
The script downloads only the pinned public base revision, disables remote model
code, trains the adapter, and saves explicit before/after evaluation.

To load this adapter, first load AutoModelForCausalLM.from_pretrained using the
model ID and exact revision above with trust_remote_code=False, then use
PeftModel.from_pretrained(base_model, './adapter', is_trainable=False).
Use the system message in train.jsonl. Do not deploy it as trusted network advice.

The raw results are retained even though the production quality gate failed.
