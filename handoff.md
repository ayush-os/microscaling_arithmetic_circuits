# Handoff — Start Here

**Status: Phase 1 complete. Starting Phase 2 next.**

If you're picking this up in a new session: read this file first, then
`spec_microscaling_arithmetic_circuit.md` for the full project spec, then
`phase1_pipeline_spec.md` for the full three-stage pipeline this phase
produced. `notes.md` has the complete process log (readings, derivations,
mistakes caught and fixed) if you need the reasoning behind any number
below — this file is just the condensed state-of-play.

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

## Phase 1 deliverable — the full pipeline spec (done)

Full writeup in `phase1_pipeline_spec.md`; reasoning trail in `notes.md`
§7. Three stages, all decided:

1. **Max-finding** — cited from HW2 directly: `log₂32=5` cycle latency,
   `31` comparators. Nothing new derived.
2. **Exponent extraction** — Phase 0's circuit, slotted in with a full
   gate-level breakdown (NOR-reduce zero-detect ∥ priority-encoder →
   adder(+119) → comparator(>254), two muxes).
3. **Requantization** — now fully designed:
   - Formula: `shared_exp = E − 127`; `P_i = Q_E4M3(V_i × 2^(−shared_exp))`
     for each of the 32 elements — always a pure bit-shift since `X` is
     a power of two, never a real divider.
   - Shifter architecture: **32 parallel shifters**, chosen because
     serial's 32 cycles would blow past the 6-cycle epilogue budget
     that HW2's own crossover analysis (k=32 = largest non-stalling
     block size) depends on — not chosen on "faster is nice" alone.
     Area cost stated honestly (~32x shifter datapath, partially offset
     by serial's own control overhead — a 32:1 input mux/sequencer/
     demux that parallel doesn't need at all).
   - Rounding mode: **round-to-nearest-even**, chosen over truncation to
     avoid truncation's directional bias, at negligible extra hardware
     cost (guard bit + sticky-OR + tie-break increment).
4. **Accumulator-precision check, stated explicitly**: 20-bit accumulator
   confirmed sufficient (worst case `2^18` fits inside `2^19−1` signed
   range; Stage 2's own verified output spread [119,137] confirms it's
   non-degenerate downstream) — by construction (N=16's whole reason for
   being), not assumed.

Total pipeline latency: 6 cycles (5 max-finding + 1 combinational for
exponent-extraction+requantization), matching HW2's own k=32 prediction
exactly.

## Phase 2 — what's next (not started)

Per the spec, build the three-stage pipeline from `phase1_pipeline_spec.md`
in real SystemVerilog/Chisel, Verilator-simulated:

- Max-finding reduction tree — real comparator instances, real tree
  structure. Verify latency (5 cycles) and comparator count (31) exactly
  match HW2's predictions — the same "does the hand-derivation survive
  contact with real RTL" discipline Phase 0/1 already applied on paper.
- Exponent-extraction logic — per the gate breakdown in
  `phase1_pipeline_spec.md` Stage 2 (NOR-reduce, priority encoder, adder,
  comparator, two muxes).
- Requantization hardware — 32 parallel barrel shifters + per-element
  RNE rounding, per Stage 3's chosen design. Real open item Phase 2 will
  need to pin down that Phase 1 deliberately deferred: exact guard/
  round/sticky bit count through the shift-then-round path.
- **End-to-end verification** against a golden Python/numpy reference
  across varied input blocks (not one test case) — brute-force where
  small enough, golden-model elsewhere, same discipline #7's own MAC
  verification used.
- **Integration point, stated concretely**: how this epilogue attaches
  after #1/#7's PE array's accumulator output.

Phase 2's deliverable per the spec: real, simulated, verified RTL for
the full dynamic-scale epilogue.

## Reference files in this repo

- `spec_microscaling_arithmetic_circuit.md` — the full project spec, all
  phases.
- `phase1_pipeline_spec.md` — Phase 1's deliverable: the full three-stage
  pipeline spec (max-finding, exponent extraction, requantization), real
  bit widths, real latency/area tradeoffs stated with one chosen —
  what Phase 2's RTL gets built against.
- `Homework 2.pdf` — CS217 HW2, source of the max-finding latency/
  comparator-count formulas and the epilogue-vs-compute latency crossover
  analysis (k=32 is the largest block size that doesn't stall this
  pipeline — real finding, in `notes.md` §1).
- `notes.md` — full process log, Phase 0 (§0–6) and Phase 1 (§7).
