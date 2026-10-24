# Development review — not release readiness

## Implemented

- Bounded paragraph retrieval (at most two passages) instead of large padded windows.
- Complete source sentences with provenance, not trusted model paraphrases.
- Whitespace-only line-wrap matching returns the original source slice; words,
  negations and qualifications cannot be rewritten by this normalization.
- Single line wraps are not treated as new sentence boundaries.
- Prefix matching handles a short source token extended in the question.
- Prompt asks for distinct quotations and independent statuses for each requested service.

## Tests and actual model calls

- 47 standard-library unit tests, zero failures/errors/skips.
- Pinned Qwen3-4B-Instruct-2507 Q4_K_M; model digest checked before inference.
- First broad-context run: three completed model calls. One accepted, one rejected
  for a duplicated quotation, one rejected for line-wrap differences. Stopped after
  these observations; subsequent cases were not completed in that run.
- Paragraph-context run: 10 development cases, **8 actual model generations** and
  2 no-hit retrieval abstentions. Original gate rejected 2 generations because of
  whitespace-only line wraps in German/English quotations.
- Those same saved outputs were then revalidated with whitespace normalization:
  both quotations map to exact original source slices. **This is not new generation**
  and not an independent accuracy result.
- Three targeted follow-up generations: service status, unknown parentage, updated
  evidence. All passed the provenance gate. The service reply included Telegram
  not-tested and YouTube failure separately. The updated-evidence reply included
  the later correction after retrieval was fixed.

## Semantic observations and remaining limitations

- The manuscript book-list reply quoted the list instead of replacing the question
  with an argument about whether the corpus was justified.
- Kafka reply quoted the transformation directly, rather than denying it.
- Shelley reply quoted family information without adding biological parentage.
- An occupation reply still included unnecessary fictional foreshadowing alongside
  the relevant occupation sentence. Provenance does not ensure focus or completeness.
- The first service reply omitted Telegram; the targeted repeat fixed this, but this
  does not establish general multi-part question reliability.
- Unknown parentage/author cases abstained without inventing an identity. An abstention
  is not a complete explanatory answer and may be less useful than a cited explanation.
- Initial updated-evidence retrieval returned no hit despite an answer in the source.
  The prefix fix and targeted repeat address this case, not general semantic retrieval.
- Paragraph-run generations took about 10–67 seconds in this desktop CPU environment;
  book answers took about 43–67 seconds. **No phone latency measurement exists.**
- These are reused/developer-designed probes, not a frozen independent holdout.

## Release state

**Not ready for an Android AI release.** No Android model runtime, model opt-in download,
phone lifecycle/RAM/battery/cancellation measurements or integrated APK verification.
There is no model browsing/execution API in this tool. It cannot manage the VPN or
publish a release. No inference was run on a volunteer Tor relay or an unconsenting server.

The pretrained weights were **not fine-tuned** in this iteration. Books were retrieval
sources, not a training corpus. This is not a claim of training on every named book,
human feelings, consciousness or unrestricted autonomous reasoning.

No user manuscript, complete book, raw model output, GGUF weight or credential is
included in the repository. Research remains separate from the standalone Tor hotfix.
