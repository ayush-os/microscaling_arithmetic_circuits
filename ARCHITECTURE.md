# Architecture

Full technical breakdown of the three-stage epilogue: real bit widths, gate-level structure, and the tradeoffs behind each design decision. See the [README](README.md) for the top-level summary.

## Scope

MXFP8, E4M3 elements, block size `k=32` (fixed by the MX spec). Takes 32 raw signed accumulator outputs from one block and produces the block's shared E8M0 exponent plus 32 requantized E4M3 elements.

## Parameters

| Parameter | Value | Reason |
|---|---|---|
| Element format | MXFP8, E4M3 | matches the target accelerator's own problem spec |
| `emax_elem` | 8 | E4M3's largest normal exponent |
| Block size `k` | 32 | fixed by the MX spec |
| MAC input width `B` | 8 bits | matches real Gemmini precedent (`SInt(8.W)`) |
| Reduction depth `N` | 16 | chosen so the resulting accumulator width matches real Gemmini's `SInt(20.W)` exactly |
| Accumulator width | 20 bits | `2B + ⌈log₂N⌉ = 20` |

Accumulator sizing check: worst-case magnitude for `N=16, B=8` is `16 × 128 × 128 = 2^18`, comfortably inside the 20-bit signed range (`2^19 − 1`). Confirmed by construction — `N=16` was chosen specifically to land here, not assumed sufficient after the fact.

## Stage 1 — Max-finding

Comparator-tree reduction over the 32 elements' magnitudes (`chisel/src/main/scala/epilogue/MaxFinder.scala`).

- **31 comparators** (`k−1`) — a `reduceTree` over 32 magnitudes produces exactly `16+8+4+2+1=31` pairwise comparisons, by construction of the tree shape.
- **5-cycle latency** (`log₂(k)`) — genuinely pipelined, not combinational: `reduceTree`'s `layerOp` hook registers every tree level's output (`RegNext`), one register stage per level. A purely combinational 5-level-deep comparator chain would also numerically "pass" a 5-cycles-later check (it settles well before that), so the pipelining had to be built deliberately, not inferred from a passing test.
- **Real edge case**: negating `SInt(20.W)`'s minimum value (`-2^19`) overflows a same-width two's-complement negation — the bit pattern wraps back to itself. Reinterpreted as unsigned, that same bit pattern happens to equal exactly `2^19`, the correct magnitude — a real, provable property of two's complement (not a coincidence specific to this test case), verified concretely rather than assumed.
- **Output**: `M`, 20-bit unsigned block-max magnitude.

## Stage 2 — Exponent extraction

Combinational, no clocked stage of its own (`ExponentExtract.scala`).

```
is_zero = NOR-reduce(M)
raw     = leading_zero_count(M)     # floor(log2(M)), via priority encoder / Log2
x       = raw + 119                 # 119 = 127 (E8M0 bias) − 8 (emax_elem)
x       = if (x > 254) 254 else x   # high clamp; 255 reserved for NaN
E       = if (is_zero) 0 else x
```

| Block | Type | Notes |
|---|---|---|
| `is_zero` | wide NOR-reduce over 20 bits | pure zero-detect, not a magnitude comparator |
| `raw` | priority encoder / LZC (`Log2`) | 20-bit in → 5-bit out (range 0–19) |
| `x` | adder, `+119` constant | uses Chisel's *growing* add (`+&`) — the plain non-growing `+` silently truncated the result to 7 bits during development, wrapping `136 → 8` for real inputs before this was caught |
| `clamp_hi` | 8-bit magnitude comparator vs. 254 | |
| `E` (final) | two 2:1 muxes | `is_zero` and the `raw→adder→comparator` chain run in parallel; the muxes chain at the end |

Verified against a concrete table spanning the design's real numeric range: `M=0→E=0`, `M=1→E=119` (smallest nonzero), `M=200,000→E=136`, `M=262,144→E=137` (theoretical ceiling for this accumulator configuration). Output range for this design is `[119, 137]` — E8M0's full 8-bit range is barely touched, a direct consequence of the accumulator's bounded dynamic range, not a bug.

## Stage 3 — Requantization

The one stage where the real RTL diverged from the paper design — see the README's "Real RTL vs. paper model" section for the story. Final architecture, per element (`Requantize.scala`):

```
shared_exp = E − 127                       # unbias E8M0
mag, sign  = |V_i|, sign(V_i)
leadingBit = Log2(mag)                     # normalize against the element's OWN leading bit
mantissa, guard, sticky = bits below leadingBit, sliced at fixed positions
                                            # after a normalizing shift places leadingBit at a fixed index
roundUp    = guard && (sticky || mantissa[0])   # round-to-nearest-even
trueExp    = leadingBit − shared_exp (+1 if rounding overflowed the mantissa)
expField   = clamp(trueExp + 7, 0, 15)      # E4M3 bias = 7
P_i        = sign ++ expField ++ mantissa
```

- **32 parallel paths, not 1 shared/serial** — decided on a latency constraint, not "faster is nice": the fixed 6-cycle compute pipeline this epilogue feeds budgets exactly 1 cycle for scale/shift/round. A serial design (32 cycles) would blow that to ~37, breaking the block-size-32 crossover point the whole design's `k=32` choice depends on. The area cost is real (~32x the per-element datapath) and stated as such, not hand-waved — partially offset by the fact that serial designs need real control overhead (input mux, sequencer, output demux) that parallel doesn't.
- **Round-to-nearest-even, not truncation** — truncation is a biased estimator (always rounds toward zero), causing directional error accumulation across every block and every layer. RNE is the default in every real low-precision format surveyed (NVIDIA FP8/E4M3, bfloat16, MX itself), at the cost of a guard bit, a sticky-OR, and a tie-break increment — a handful of gates next to the normalizing shifter already in the design.
- **Mantissa-overflow-into-exponent handled explicitly**: rounding `111→1000` correctly bumps the exponent field by one rather than silently wrapping the mantissa, verified against a concrete tie-breaking case that exercises exactly this path.
- **Known simplification, stated rather than assumed**: exponent under/overflow after the `shared_exp` adjustment clamps to E4M3's representable range (flush-to-zero-exponent / saturate) instead of a full IEEE-style subnormal encoding. Neither the OCP MX spec section read for this project nor any other source consulted pinned this case down definitively — this is a documented engineering choice, not an oversight.

## Bit-width summary

| Signal | Width | Stage |
|---|---|---|
| `V_i` (raw accumulator element) | 20-bit signed | input |
| `M` (block max magnitude) | 20-bit unsigned | 1→2 |
| `raw` (LZC output) | 5-bit unsigned | 2 internal |
| `E` (E8M0 shared exponent) | 8-bit unsigned | 2→3, block output |
| `shared_exp` (unbiased) | 9-bit signed (`io.e.zext − 127.S`) | 3 internal; realistic range ≈[−8,+10] |
| `P_i` (E4M3 element) | 8-bit (1 sign + 4 exp + 3 mantissa) | 3, block output |

## Latency / area summary

- **Total latency**: 5 cycles (Stage 1, registered tree) + 1 cycle (Stages 2+3, combinational) = **6 cycles** — matches the compute pipeline's own fixed 6-cycle budget exactly.
- **Total area** (qualitative — no synthesis has been run, see Known Gaps): 31 comparators (Stage 1) + one small combinational circuit (Stage 2) + 32 parallel per-element normalize/round paths (Stage 3, the dominant cost, chosen deliberately over a smaller serial alternative for the latency reason above).
- **Compression**: `32 × 8 + 8 (shared exponent) = 264 bits` vs. `32 × 20 = 640 bits` raw — 2.4x smaller, a 3.0% metadata tax over the pure 8-bit payload.

## Known gaps

- No broad randomized/fuzz sweep against an independent external reference (e.g. numpy/`ml_dtypes` native E4M3 casting) — current test coverage is randomized for Stage 1, hand-computed/formula-derived for Stages 2–3 and the end-to-end composition test.
- No full IEEE-style subnormal encoding on exponent underflow (see Stage 3 above).
- No synthesis run against a real standard-cell library (e.g. sky130) — every number above comes from RTL simulation (Verilator), not a synthesized netlist. No real area/power/timing numbers exist for this design yet.
