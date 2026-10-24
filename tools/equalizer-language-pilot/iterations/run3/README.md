# Run 3 — rejected continuation

Continued the run2 adapter for 90 more optimizer steps on the same 90 synthetic
dialogues. Cumulative lineage: 180 steps. This is not training from scratch.
The eight validation answers had already been inspected; no blind benchmark claim.

Validation token loss decreased slightly, but manual reading found unsupported
Telegram availability and an incorrect suggestion to train on a Tor relay. Those
outputs were not executed. This checkpoint is rejected for app integration and
network advice. No model is promoted based on loss alone.

The release candidate does not include any of these experimental adapters.
The run2 experimental adapter and full reproduction data are in the downloadable
Equalizer-language-pilot-experimental.zip. Run3 source expects a sibling
run2/adapter directory with those parent weights and the same pinned base model.
