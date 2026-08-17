# Handoff — Start Here

**Status: Phase 0 complete. Starting Phase 1 next.**

If you're picking this up in a new session: read this file first, then
`spec_microscaling_arithmetic_circuit.md` for the full project spec.
`notes.md` has the complete process log (readings, derivations, mistakes
caught and fixed) if you need the reasoning behind any number below —
this file is just the condensed state-of-play.

## What this project is

Building the one real missing circuit in an MX (microscaling) accelerator
epilogue: the piece that takes a block of 32 raw accumulator outputs and
computes the shared E8M0 exponent for them, dynamically, in hardware —
per-block, not a compile-time constant. Max-finding and requantization
are the other two epilogue stages; max-finding is reused wholesale from
prior coursework, requantization hasn't been designed yet (that's Phase
1's job). Full scope/phase breakdown is in the spec file.

## Decided parameters (all justified in `notes.md` §2–3)

| Parameter | Value | Source/reason |
|---|---|---|
| Element format | MXFP8, E4M3 elements | matches HW2's own problem exactly |
| `emax_elem` | 8 | E4M3's largest normal exponent |
| Block size `k` | 32 | fixed by the MX spec |
| MAC input width `B` | 8 bits | matches real Gemmini (`SInt(8.W)`) |
| Reduction depth `N` | 16 | chosen so accumulator width matches real Gemmini exactly |
| Accumulator width | 20 bits | `2B + ⌈log₂N⌉ = 20`, = real `SInt(20.W)` precedent |

"#1" and "#7" in the spec = the author's own prior projects
(`vliw-isa-and-scheduler`, `workload-to-silicon`), not course material —
see `notes.md` §0 if that comes up again.

## Phase 0 deliverable — the exponent-extraction circuit (done)

```
if M == 0:
    return 0
raw = leading_zero_count(M)      # = floor(log2(M)), via priority-encoder
x = raw + 119                    # 119 = 127 (E8M0 bias) − 8 (emax_elem)
if x > 254: x = 254              # clamp high; 255 is reserved for NaN
return x
```

- Input: `M`, a 20-bit unsigned magnitude (the block's max, from the
  reused max-finding tree — `log₂32=5` cycles, 31 comparators).
- Output: 8-bit E8M0 exponent.
- Verified against concrete numbers (M=0, 1, 200,000, boundary 262,144)
  — see `notes.md` §4 for the verification table and two real bugs caught
  along the way (a sign error, an out-of-range test value) before this
  version was settled on.
- The low clamp (`x < 0`) is real but provably dead code for this
  design's numeric range — kept for robustness, documented as unreachable.

## Phase 1 — what's next (not started)

Per the spec, Phase 1's deliverable is the full three-stage pipeline spec,
written up together:

1. **Max-finding** — just cite HW2's own formulas directly (`log₂k`
   latency, `k−1` comparators for k=32). Nothing to derive.
2. **Exponent extraction** — done above, slot it in as-is.
3. **Requantization** — **undesigned, start here.** Given the shared
   exponent E from stage 2, shift+round all 32 raw accumulator values
   into E4M3. Real open questions, not yet touched:
   - One shared barrel shifter reused 32× (smaller/slower) vs. 32
     parallel shifters (larger/one-cycle) — work out the actual
     area/latency tradeoff, don't assume an answer.
   - Rounding mode: round-to-nearest-even vs. truncation.
4. **State explicitly, as its own checked item** (not just inherited from
   Phase 0's notes): does the 20-bit accumulator actually carry enough
   bits for max-finding/exponent-extraction to be meaningful at this
   stage? (Short answer already worked out in `notes.md` §3 — N was
   chosen specifically to make this check pass by construction — but
   Phase 1's writeup should state it as a deliberate check, not silently
   inherit it.)

Phase 1's actual deliverable per the spec: one written pipeline doc,
three stages, real bit widths, real latency/area tradeoffs stated with
one chosen — ready to become Phase 2's RTL.

## Reference files in this repo

- `spec_microscaling_arithmetic_circuit.md` — the full project spec, all
  phases.
- `Homework 2.pdf` — CS217 HW2, source of the max-finding latency/
  comparator-count formulas and the epilogue-vs-compute latency crossover
  analysis (k=32 is the largest block size that doesn't stall this
  pipeline — real finding, in `notes.md` §1).
- `notes.md` — full Phase 0 process log.
