# Phase 1 — Full Epilogue Pipeline Spec

**Status: complete.** Three stages, real bit widths, real latency/area
tradeoffs stated with one chosen each — ready to become Phase 2's RTL.
Derivation/reasoning behind every decision below lives in `notes.md`
(Phase 0: §1–5; Phase 1: §7).

Scope: takes k=32 raw accumulator outputs from one MX block and produces
the block's shared E8M0 exponent plus 32 requantized E4M3 elements.
MXFP8, E4M3 elements, k=32, N=16, B=8, 20-bit signed accumulator
(`SInt(20.W)`) — all justified in `notes.md`.

---

## Stage 1 — Max-finding

Reused directly from HW2, no new derivation.

- **Structure**: comparator reduction tree over the 32 accumulator
  magnitudes.
- **Comparator count**: `k − 1 = 31`.
- **Latency**: `log₂(k) = 5` cycles (tree depth).
- **Output**: `M`, the block's max magnitude, 20-bit unsigned — feeds
  Stage 2.

## Stage 2 — Exponent extraction

Phase 0's circuit, slotted in as designed and verified (`notes.md` §4).

```
if M == 0:
    return 0
raw = leading_zero_count(M)      # = floor(log2(M)), via priority-encoder
x = raw + 119                    # 119 = 127 (E8M0 bias) − 8 (emax_elem)
if x > 254: x = 254              # clamp high; 255 is reserved for NaN
return x
```

**Circuit** (combinational, no separate clock stage):

| Block | Type | Notes |
|---|---|---|
| `is_zero` | wide NOR-reduce over 20 bits | not a magnitude comparator — pure zero-detect |
| `raw` | priority encoder / LZC | 20-bit in → 5-bit out (range 0–19) |
| `x` | adder, +119 constant | 5-bit `raw` zero-extended, sum fits 8 bits (max 138) |
| `clamp_hi` | 8-bit magnitude comparator vs. 254 | |
| `x_clamped` | 2:1 mux, `clamp_hi ? 254 : x` | |
| `E` (final) | 2:1 mux, `is_zero ? 0 : x_clamped` | |

`is_zero` and the `raw→adder→comparator` chain run in parallel (both
read `M` only); the two muxes chain at the end. Low clamp (`x < 0`) is
provably dead code for this design's numeric range (`notes.md` §4.6) —
not built.

- **Input**: `M`, 20-bit unsigned.
- **Output**: `E`, 8-bit E8M0-biased exponent, verified range [119, 137]
  for this accumulator's numeric ceiling (`notes.md` §4, verification
  table).

## Stage 3 — Requantization

The one stage that was undesigned entering Phase 1. Given `E` from
Stage 2, shift and round all 32 raw accumulator values into E4M3.

**Formula** (mirrors OCP MX's own quantize direction, `notes.md` §1):

```
shared_exp = E − 127                          # unbias E8M0
P_i = Q_E4M3(V_i × 2^(−shared_exp))           for i = 0..31
```

`X = 2^shared_exp` is always a power of two, so `V_i / X` is a pure
signed bit-shift (right-shift when `shared_exp > 0`, left-shift when
`shared_exp < 0` — genuinely bidirectional; verified range of
`shared_exp` is roughly [−8, +10] for this design). `Q_E4M3(·)` then
re-normalizes the shifted magnitude into E4M3's 1-sign/4-exp/3-mantissa
grid: sign strips off first, a per-element LZC finds the local leading
bit, top 3 bits become the mantissa under the rounding rule below.

### Decision: 32 parallel shifters (not 1 shared, time-multiplexed)

| | Serial (1 shifter × 32 cycles) | Parallel (32 shifters × 1 cycle) |
|---|---|---|
| Latency | 32 cycles | 1 cycle |
| Shifter area | ~1x | ~32x |
| Extra control | 32:1 input mux, cycle counter, output demux/routing | none — no time-multiplexing to manage |

- **Latency is decisive, not just "faster is nice"**: HW2's own model
  budgets exactly 1 cycle for "scale/shift/round" inside the 6-cycle
  total epilogue latency that makes k=32 the largest block size that
  doesn't stall the fixed 6-cycle compute pipeline (`notes.md` §1,
  Part C.a). Serial's 32 cycles would blow that budget to ~37 cycles,
  breaking the crossover finding this project's own k=32 choice rests
  on. Parallel is effectively required by an already-established
  constraint, not chosen on a latency/area coin-flip.
- **The area cost is real (~32x the shifter datapath) but not a clean
  32x for the whole design**: the shift-amount decode is a single
  shared value (`shared_exp` is one number per block) broadcast to all
  32 data paths either way — that part doesn't scale 32x under either
  architecture, so it's a wash, not a parallel-specific saving. What
  *does* differ is that serial must add control parallel never needs
  (32:1 mux, sequencer, output demux) purely to time-multiplex one
  physical shifter — so serial's true cost is understated by "1x" once
  that overhead is counted.
- **Chosen: parallel.** Fits the established latency budget; area cost
  is real and stated, not hand-waved.

### Decision: round-to-nearest-even (not truncation)

- Truncation is a biased estimator (always rounds toward zero) — error
  compounds in one direction across every block, every layer, causing
  systematic accuracy drift, exactly the failure mode a quantized
  pipeline can't afford.
- RNE is the default rounding mode in every real format this project
  read during Phase 0 (NVIDIA FP8/E4M3, bfloat16, MX itself) — the
  established convention for this exact bias reason, not an outlier
  choice.
- Hardware cost is small: a guard-bit check on the first discarded bit,
  a sticky-OR over the remaining discarded bits, and a tie-break on the
  kept mantissa's LSB feeding a +1 increment — a handful of gates next
  to the barrel-shifter/LZC hardware already in the pipeline. Exact
  guard/round/sticky bit count to be pinned down at Phase 2 RTL time.
- **Chosen: round-to-nearest-even.** Negligible extra cost for a real
  accuracy win; not a close call.

### Output format and compression

Each of the 32 elements: 1 sign + 4-bit exponent (bias 7) + 3-bit
mantissa = 8 bits (E4M3). Full quantized block: `32 × 8 + 8 (shared
exponent) = 264 bits`, vs. `32 × 20 = 640 bits` of raw accumulator
input — consistent with HW2's own cited k=32 memory-overhead figure
(shared exponent = 8/256 ≈ 3.0% tax over the 32×8-bit payload,
`notes.md` §1).

---

## Explicit check: does the 20-bit accumulator carry enough bits to make Stages 1–3 meaningful?

- Worst-case accumulator magnitude for this design's own B=8, N=16:
  `16 × 128 × 128 = 262,144 = 2^18`.
- 20-bit signed range covers magnitudes up to `2^19 − 1 = 524,287` —
  the real worst case sits comfortably inside it (19 of 20 bits used
  even at the theoretical ceiling). No overflow, no silent wraparound.
- Confirmed non-degenerate downstream: Stage 2's verification table
  produces `E` spanning [119, 137], a real spread rather than every
  block collapsing to one clamped value.
- **Answer: yes, sufficient — by construction, not luck.** N=16 was
  chosen (over the initial N=k=32 instinct) specifically so this width
  would land exactly on real Gemmini's `SInt(20.W)` precedent while
  still leaving this headroom (`notes.md` §3).

---

## Bit-width summary

| Signal | Width | Stage |
|---|---|---|
| `V_i` (raw accumulator element) | 20-bit signed | input |
| `M` (block max magnitude) | 20-bit unsigned | 1→2 |
| `raw` (LZC output) | 5-bit unsigned | 2 internal |
| `E` (E8M0 shared exponent) | 8-bit unsigned | 2→3, block output |
| `shared_exp` (unbiased) | ~5-bit signed (range ≈[−8,+10]) | 3 internal |
| `P_i` (E4M3 element) | 8-bit (1 sign + 4 exp + 3 mantissa) | 3, block output |

## Latency/area summary

- **Total latency**: 5 cycles (Stage 1, registered tree) + 1 cycle
  (Stages 2+3, combinational) = **6 cycles**, matching HW2's own
  Part C.a prediction for k=32 exactly.
- **Total area (qualitative, real numbers deferred to Phase 3
  synthesis)**: 31 comparators (Stage 1) + one small combinational
  exponent-extraction circuit (Stage 2) + 32 parallel 20-bit barrel
  shifters plus 32 small per-element LZC/RNE-rounding units (Stage 3,
  the dominant area cost, chosen deliberately over a smaller serial
  alternative for the latency reason above).

**Ready for Phase 2**: real SystemVerilog/Chisel, Verilator-simulated,
per the spec's toolchain.
