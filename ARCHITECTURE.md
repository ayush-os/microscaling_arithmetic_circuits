# Architecture

Full technical breakdown of the three-stage epilogue: real bit widths, gate-level structure, and the tradeoffs behind each design decision. See the [README](README.md) for the top-level summary. The design here is the **current, combinational** epilogue; its 6-cycle pipelined predecessor and why it was replaced is covered in "Design history" below, with that version's own real numbers preserved for comparison.

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

`B`/`N` are, like the old 6-cycle latency contract discussed below, inherited from a Gemmini reference design this project doesn't integrate with — reviewed under the same lens and **kept**, because they fix the accumulator width at a value that's independently reasonable for this kind of block on its own terms, not just as an artifact of matching an external pipeline. Two choices from the same origin, two different fates: only the latency contract turned out to be pure overhead.

## Stage 1 — Max-finding

Comparator-tree reduction over the 32 elements' magnitudes (`chisel/src/main/scala/epilogue/MaxFinder.scala`). Purely combinational.

- **31 comparators** (`k−1`) — a level-by-level fold over 32 magnitudes produces exactly `16+8+4+2+1=31` pairwise comparisons, by construction of the tree shape.
- **No registers anywhere** — an earlier version pipelined this tree into 5 genuine cycle stages (see "Design history" below for why, and for a real bug found along the way). Real PPA data showed that pipelining bought nothing: Stage 2→3's own chain, not this tree, was always the actual critical path. Now a single unbroken combinational chain from `io.in` to `io.out`.
- **Real edge case**: negating `SInt(20.W)`'s minimum value (`-2^19`) overflows a same-width two's-complement negation — the bit pattern wraps back to itself. Reinterpreted as unsigned, that same bit pattern happens to equal exactly `2^19`, the correct magnitude — a real, provable property of two's complement (not a coincidence specific to this test case), verified concretely rather than assumed.
- **Output**: `M`, 20-bit unsigned block-max magnitude.

## Stage 2 — Exponent extraction

Combinational (`ExponentExtract.scala`) — was always this way, unaffected by Stage 1's pipelining or its removal.

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

- **32 parallel paths, not 1 shared/serial** — still true today, but the original reasoning ("the fixed 6-cycle compute pipeline budgets exactly 1 cycle for scale/shift/round, a serial design would blow that to ~37 cycles") no longer applies now that the 6-cycle contract is gone (see "Design history"). Requantize is the single largest line item in the design (58% of real cell count) and is the biggest untapped area target precisely because this justification evaporated — see "Future directions" for the serialization option and its real, unresolved interface question.
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

## Real physical numbers — current design (OpenLane 2 + sky130hd)

Toolchain: OpenLane 2 (Yosys + OpenROAD), SkyWater `sky130_fd_sc_hd`, real STA across all 9 PVT corners. Design lives in `physical/designs/epilogue-merged/`; reasoning for each config choice is inline in its `config.yaml`.

**Confirmed zero registers, not just a code-review claim**: Yosys's synthesis log reports "of which used for sequential elements: 0.00%"; OpenROAD's CTS step independently logged `Net "clock" has 0 sinks. Skipping...` / `No clock nets have been found` — tool-confirmed at both the gate-level netlist and the placed-and-routed layout, not asserted.

**Real area**:

| | Cells | Area (µm²) |
|---|---|---|
| Post-synthesis (Yosys, pre-place) | 8,950 | 85,290.55 |
| Post-PnR (placed + routed) | — | 101,809 |

**What "Fmax" means for a zero-register design.** There's no periodic clock-closure question here — no register feeds back into itself, so "how fast can this clock go, repeated every cycle" doesn't parse. What's meaningful instead is a one-shot propagation delay: how long from a fresh `io.v` arriving until `io.e`/`io.p` are valid. `Epilogue` kept its (unused) `clock`/`reset` port specifically so OpenLane's standard SDC machinery could still produce this number — `CLOCK_PERIOD` was set to a deliberately generous, un-swept 50ns (not a tuned Fmax target) purely so OpenSTA would run and report real arrival times. The number that matters is `data arrival time − 10ns input-delay budget`, read directly off `report_checks`, and doesn't depend on which period was chosen, as long as it's generous enough for placement/routing to complete normally (confirmed: DRC/LVS both passed at 50ns).

| Corner | Real propagation delay (input pin → output pin) |
|---|---|
| Typical (`tt_025C_1v80`) | 20.85 ns |
| Worst-case all-PVT (`ss_100C_1v60`) | 40.12 ns |

**Real power** (OpenROAD's built-in vectorless estimate, nominal corner): **25.54mW** total — 9.24mW internal (36%), 16.29mW switching (64%), leakage negligible. Not a switching-activity-accurate signoff — see Known Gaps.

**DRC/LVS**: both clean. Antenna check failed (32 pin, 25 net violations) — a real DFM/fab-readiness category, explicitly out of this project's scope.

## Design history: the 6-cycle pipelined predecessor

The current combinational design replaced an earlier one that pipelined Stage 1 into a genuine 5-cycle comparator tree and carried a `ShiftRegister(io.v, 5)` delay line in `Epilogue.scala` to keep raw `V_i` values aligned with Stage 1's latency — 6 cycles total. That number was never derived from this block's own timing needs; it existed to match an external, fixed-latency compute pipeline (a CS217/Gemmini-style MAC array) this epilogue was originally scoped to plug into: that pipeline hands off a finished accumulator block every cycle, on a fixed schedule, so the epilogue had to consume and produce on that same cadence to avoid stalling it. This project has no such integration target and never will, which made the 6-cycle contract arbitrary rather than load-bearing — the reason it was removed.

### The MaxFinder bug, found via real synthesis

Registered between every tree level via `Vec.reduceTree(op, (x: UInt) => RegNext(x))`, trusting the `layerOp` hook to register every level — 5 levels for 32 inputs, intended as a real 5-stage pipeline (short critical path per stage, instead of 5 comparators in series). A purely combinational 5-level-deep comparator chain would also numerically "pass" a 5-cycles-later simulation check (it settles well before that), so the pipelining had to be built deliberately, not inferred from a passing test — and this claim was, for a real stretch, false anyway.

Real synthesis produced a `MaxFinder` netlist with **zero registers**. Root cause: `reduceTree`'s `layerOp` only fires on elements "promoted" past an unpaired leftover at odd-sized levels, plus the final single-element base case — for a power-of-two input like 32, every level divides evenly, so that hook is never invoked at all. Simulation couldn't catch it because every test held inputs constant across the whole 5-cycle window, exactly the blind spot the design itself had already named in prose; real synthesis did. Fixed at the time by building the tree explicitly with `RegNext` applied unconditionally per level, with new tests that switch inputs mid-pipeline specifically to distinguish "genuinely pipelined" from "settles fast." That fix's real registers were themselves later removed as part of the redesign below — the bug and the redesign are separate findings that happened to land on the same module.

### Why pipelining Stage 1 bought nothing

The design's real critical path was never Stage 1's comparator tree — it's the unbroken combinational chain through Stage 2 → Stage 3 (LZC → add → clamp → LZC again → barrel shift → round → clamp → pack, no register anywhere in it), which alone set the clock period at 11–30ns depending on PVT corner (see below). One global clock period governs every stage, so Stage 1's 5 pipeline stages — each doing one comparison's worth of work — each still paid the full, slow, Stage-2+3-dominated cycle budget. Removing Stage 1's registers (and the `ShiftRegister` that existed only to stay aligned with them) costs throughput: the new critical path becomes `period(Stage 1) + period(Stage 2+3)`, strictly ≥ the old `period(Stage 2+3)` alone, so a new block can no longer start every cycle the way it could before. But Stage 1's depth is small relative to Stage 2+3's, so that hit is bounded — while the area and latency freed up are not. Same shape as Amdahl's law: speeding up (here, deregistering) the part that was never the bottleneck buys a large structural win at a small, predictable cost to the one thing it does trade away.

### The old design's own real numbers

Real PVT-corner-signed-off synthesis + PnR for the 6-cycle pipelined design, same toolchain:

**Per-stage area** (standalone synthesis):

| Stage | Cells | Area (µm²) | Share |
|---|---|---|---|
| 3 — Requantize | 7,225 | 71,909.0 | 58.2% |
| 1 — MaxFinder | 4,601 | 51,184.1 | 41.5% |
| 2 — ExponentExtract | 43 | 372.9 | 0.3% |

Composed design (post-synthesis): 14,968 cells, 189,424.2 µm², 3,820 flip-flops (`31×20` MaxFinder tree bits + `160×20` `ShiftRegister(io.v, 5)` bits, exactly). Post-PnR placed/routed instance area: 225,001 µm² (die 657,717 µm², core 630,960 µm², 35.7% utilization).

**Real Fmax** — two numbers, because OpenLane's own default signoff (`TIMING_VIOLATION_CORNERS: ["*tt*"]`) only gates on the typical corner, not the full PVT range a real chip has to survive:

| Signoff bar | Period | Fmax | 6-cycle latency |
|---|---|---|---|
| Typical corner only (`tt_025C_1v80`) | 11ns | 90.9 MHz | 66ns |
| All 9 PVT corners, incl. worst-case `ss_100C_1v60` | 30ns (clean; 23ns confirmed still violating, true boundary unresolved tighter than that) | 33.3 MHz | 180ns |

Real power (nominal corner, at the all-corner-clean 30ns/33.3MHz operating point): 17.49mW total. DRC/LVS both clean; antenna check failed (24 pin, 22 net violations), same out-of-scope category as the current design's own antenna failures (32 pin, 25 net).

### The redesign's real numbers, checked against a naive estimate

Before rebuilding anything, the win was estimated by summing the three stages' standalone-synthesized combinational-only areas (`37,996.4 + 372.9 + 71,909.0 = 110,278.3 µm²`) — a 41.8% reduction from the old composed total. Real merged synthesis came in at 85,290.55 µm², **22.7% lower than even that estimate**. Cell count tells the same story: 8,950 real cells vs. `4,601 + 43 + 7,225 = 11,869` summed from the standalone per-stage runs, a 24.6% reduction. The gap is explained, not just noted: the estimate summed three separate, isolated synthesis runs, each blind to the other two modules' logic; the real merged netlist is one flattened design, and Yosys's SAT-based resource-sharing pass (visible directly in the synthesis log, explicitly searching for shareable cell pairs) finds real logic sharing across what used to be module boundaries that three separate synthesis runs structurally cannot see.

Post-PnR area (101,809 µm²) grew 19.4% over post-synthesis — nearly identical to the *old* design's own post-synthesis→post-PnR growth (18.8%), a nice independent sanity check that this class of overhead (fill/tap cells, buffering, legalization) is fairly predictable regardless of design size. Comparing full post-PnR to full post-PnR: **101,809 µm² vs. 225,001 µm², a 54.7% reduction** — bigger than the naive 41.8% synthesis-level estimate predicted, for the same cross-stage-sharing reason.

Latency: real one-shot propagation delay (20.85ns typical / 40.12ns worst-case) vs. the old design's real 6-cycle latency (66ns / 180ns) is a **3.2x reduction at the typical corner, 4.5x at the honest all-corner bar** — a bigger win at the corner that actually matters for a real chip, not a smaller one.

Power came out higher in the new design (25.54mW vs. 17.49mW) but this is **not a clean comparison**: OpenROAD's vectorless power model ties assumed net toggle rates to the declared clock period, and the two designs declare different periods (50ns generous bookkeeping vs. 30ns real signed-off Fmax) for structurally different reasons — reported honestly, not used to draw a real conclusion either way.

One real execution snag, worth keeping as a general lesson: the same 906 flat IO pins (32×20-bit input bus + 32×8-bit output bus + control) that constrained the old design's floorplan constrained this one too, but in the *opposite* direction — the old design hit OpenROAD's IO-pin-placement error (PPL-0024) because it was too big at high utilization; the new design hit the identical error because it got *smaller* than expected while the same fixed pin count needed the same fixed perimeter, so the utilization that worked before (30%) was no longer generous enough for the new, physically smaller die. Backed the fix out algebraically from the reported perimeter shortfall (needed ~1.4x more perimeter → ~1.96x more die area → utilization needed to drop from 30% to ~15.3%) rather than blindly re-bisecting; picked 12% for margin, worked on the first retry. Lesson: with this high an IO-pin-to-logic ratio, floorplan utilization has to be re-derived from real cell area every time core area changes materially, not copied from a prior run in either direction.

## Known gaps

- No broad randomized/fuzz sweep against an independent external reference (e.g. numpy/`ml_dtypes` native E4M3 casting) — current test coverage is randomized for Stage 1, hand-computed/formula-derived for Stages 2–3 and the end-to-end composition test.
- No full IEEE-style subnormal encoding on exponent underflow (see Stage 3 above).
- No fab-ready signoff: antenna violations remain on both the current and historical design, and this is not a full manufacturability signoff.
- Power is OpenROAD's built-in static estimate, not a real switching-activity/vector-based power signoff (a legitimately separate, longer project) — and the current design's power number isn't directly comparable to the old design's for the declared-period reason above.
- The old pipelined design's all-PVT-corner Fmax boundary was bracketed (violates at 23ns, clean at 30ns) but never pinned tighter — deliberately not chased further, matching this project's own discipline against pursuing signoff-perfection past the point of being meaningful.

## Future directions (identified, not built)

Three directions beyond the current combinational design are real, specifically-scoped options for whoever picks this up next — not vague ideas, but not equivalent-effort siblings of the merged variant either. Deleting Stage 1's registers was a subtraction; each of these is new RTL design with a real open question of its own.

- **Rebalanced pipeline.** Bring pipelining back — recover the throughput the current design trades away — but put the registers where the real delay is instead of at the old Stage 1/Stage 2+3 module boundary. First step, not optional: pull a real OpenSTA `report_checks -path_delay max` critical-path trace on the current synthesized netlist to find where delay actually concentrates in the `ExponentExtract`→`Requantize` chain (LZC → add → clamp → LZC again → barrel shift → round → clamp → pack) — don't guess cut points from the block diagram. Once real cut points are known, insert registers there, which means restructuring `ExponentExtract`/`Requantize` to have pipeline boundaries *inside* what are currently single combinational modules, not just at module edges. This is the only one of the three directions that can beat the old 6-cycle design's throughput rather than just avoid losing it, because it can shrink the current single-shot 11–30ns Stage 2+3 delay below its own un-cut value — worth it precisely because Stage 2+3, not Stage 1, was always the bottleneck. Re-verification needs the same "genuinely pipelined vs. settles fast" mid-flight-input-switch test style already used once for Stage 1 (and since removed, since it had nothing left to test), reapplied at the new cut points.

- **Serialize Requantize.** Replace the 32 parallel normalize/round/pack datapaths with one shared datapath, reused 32x via a mux (selects which `v_i` feeds the shared datapath this cycle) and a counter (tracks which element index is active). Biggest remaining area target by a wide margin — Requantize alone is 58% of real cell count, the single largest line item in the whole design. Real, unresolved interface question, not a detail to defer: does `io.p` still present all 32 elements at once (needs an internal 32-wide output register bank, filled over 32 cycles — real area added back, partially offsetting the serialization win) or does it stream `p_i` out one at a time with an index/valid signal (cheaper, but changes `Epilogue`'s output interface shape entirely, and pushes the "gather all 32" burden onto whatever consumes it — which doesn't exist yet in this project's scope). Either answer is defensible; neither is free, and the choice has to be made before writing the module, not discovered while writing it.

- **Serialize MaxFinder.** Same shape: one shared comparator plus a running-max register (`reg := (v_i.mag > reg) ? v_i.mag : reg`), reused across 32 cycles instead of the 31-comparator tree. Smaller payoff than serializing Requantize — MaxFinder is a smaller share of real cell count. Notably simpler interface question than Requantize's, worth naming as an asymmetry: MaxFinder only ever produces one scalar (`M`), so there's no "present N outputs at once vs. stream them" decision to make — serializing the comparator doesn't create a new interface shape the way serializing Requantize does.

None of these three are parameter tweaks — each needs its own real RTL design pass (finding real critical-path cut points, or designing a serialized datapath with a real interface decision) and its own synthesis/PnR pass to get real numbers, the same discipline applied to the current design above. They're independent of each other and could in principle be combined (e.g. a rebalanced pipeline with a serialized Requantize stage), but that compounds the scope further rather than reducing it.
