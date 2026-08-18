# Dynamic-Scale MX Epilogue

Synthesized-and-placed MXFP8 microscaling epilogue — computes a 32-element block's shared E8M0 exponent dynamically from real accumulator data and requantizes all 32 elements against it, as a single combinational block after real PPA data showed an inherited pipelining assumption was pure overhead.

**Chisel 7 → Verilog (firtool/CIRCT) → Yosys + OpenROAD → SkyWater sky130hd · MXFP8 (E4M3 elements, E8M0 shared exponent), k=32 · fully synthesized and placed & routed, signed off across all 9 PVT corners**

- **101,809 µm² post-PnR, 20.85–40.12ns end-to-end propagation delay** (typical → worst-case PVT corner) — down from an earlier 6-cycle pipelined version's real 225,001 µm² / 66–180ns: **54.7% smaller, 3.2–4.5x lower latency**, for a small, quantified throughput cost, after finding the pipeline's 6-cycle latency contract was inherited from an external interface this project never targets. Real numbers on both sides, not an estimate — see "Design evolution" below.
- **Real synthesis caught a bug simulation couldn't**: a comparator tree assumed to be a genuine 5-stage pipeline was silently elaborating with **zero registers** — a `Vec.reduceTree` API footgun invisible to any held-constant-input test, found by reading the actual gate-level netlist and root-caused to the library source, not guessed at.
- **All-9-PVT-corner signoff, not just the typical case**: OpenLane's own default pass/fail gate only checks the nominal corner — the same design that reports "clean" by default needed a meaningfully longer period to actually close timing at the slow/hot/low-voltage corner a real chip has to survive.

## What this is

The one real missing piece in an MX accelerator epilogue. Max-finding and a static-scale rescale circuit already existed from prior coursework; this project is specifically the gap between them — a genuinely data-dependent circuit that recomputes the shared scale fresh from real data every block, rather than a scale/shift pair chosen once and baked in at compile time. It reuses the max-finder tree's comparator primitive as given and adds only the dynamic-scale computation and requantization logic on top.

## Architecture

Three stages, one unbroken combinational chain, no internal registers:

| Stage | Function | Real cost (post-synthesis) |
|---|---|---|
| 1. Max-finding | 31-comparator tree over 32 signed accumulator elements' magnitudes | 4,601 cells |
| 2. Exponent extraction | NOR-reduce zero-detect ∥ priority-encoder → `+119` offset adder → high clamp | 43 cells |
| 3. Requantization | 32 parallel per-element normalize + round-to-nearest-even into E4M3 | 7,225 cells (58% of the design) |

Combined, real-synthesized: 8,950 cells, 85,290.55 µm² post-synthesis / 101,809 µm² post-PnR, **0.00% sequential** (tool-confirmed, not asserted — Yosys reports zero sequential cells, OpenROAD's CTS step logs "no clock nets found").

## Design evolution: from a 6-cycle pipeline to one combinational block

The original design pipelined Stage 1 into a genuine 5-cycle comparator tree and carried a `ShiftRegister(io.v, 5)` delay line to keep raw accumulator values aligned with it — 6 cycles total, chosen to exactly match an external, fixed-latency compute pipeline (a CS217/Gemmini-style MAC array) this epilogue was originally scoped to plug into.

This project has no such integration target. Auditing that assumption against real synthesis numbers showed pipelining Stage 1 bought nothing: the actual critical path was never the comparator tree, it was Stage 2→3's own unbroken combinational chain (LZC → add → clamp → LZC again → barrel shift → round → clamp → pack), which alone set the clock period regardless of anything Stage 1 did. One shared clock period governs every stage, so Stage 1's 5 pipeline stages — each doing one comparison's worth of work — were each paying the full, slow, Stage-2+3-dominated cycle budget for nothing.

Removing Stage 1's registers (and the delay line that existed only to stay aligned with them) trades a small, bounded throughput cost — a new block can no longer start mid-flight the way it could in the pipelined version — for the area and latency numbers above, which came in *better* than the naive estimate: real merged synthesis found real cross-stage logic sharing that three separately-synthesized per-stage estimates structurally couldn't see (8,950 real cells vs. 11,869 summed from standalone per-stage runs).

Three further directions — rebalancing pipeline registers onto the actual Stage 2/3 bottleneck (the only lever that improves throughput), and serializing Requantize/MaxFinder into shared, reused datapaths for further area cuts — are identified and scoped but not built; see `ARCHITECTURE.md`'s "Future directions."

## RTL vs. model: the MaxFinder bug

`MaxFinder.scala` built its comparator tree with `Vec.reduceTree(op, (x: UInt) => RegNext(x))`, trusting the `layerOp` hook to register every tree level. Real synthesis produced a netlist with zero registers. Root cause, found in chisel's own source: `layerOp` only fires on elements "promoted" past an unpaired leftover at odd-sized levels, plus the final single-element base case — for a power-of-two input like 32, every level divides evenly, so the hook is never invoked at all, for any power-of-two-sized `reduceTree` call. No amount of simulation could have caught this: every test held inputs constant across the check window, which a genuinely-pipelined design and a fast-settling combinational one both satisfy identically. Fixed by building the tree explicitly; new tests specifically switch inputs mid-flight to distinguish the two cases.

(Moot for the current combinational design, but the finding stands as the reason real synthesis — not just simulation — is load-bearing for anything claiming a specific pipeline structure.)

## Verification

- Per-stage unit tests: `MaxFinder` (randomized inputs + the `SInt` two's-complement minimum-value edge case, plus a same-cycle-settling check), `ExponentExtract` (concrete verification table spanning the zero/boundary/max cases), `Requantize` (hand-computed E4M3 round-to-nearest-even cases, including the mantissa-overflow-into-exponent path).
- End-to-end composition test (`EpilogueSpec`): random 32-element blocks through the full wired pipeline, checked against an independently-transcribed golden exponent formula and a *separately* instantiated `Requantize` module, plus a back-to-back-blocks-with-no-settling-time test — the composition-level check that actually matters for a combinational design (no latency for `v`/`e`/`p` to drift out of alignment across).

**Known gaps, stated rather than hidden**: no broad randomized/fuzz sweep against an independent external reference (e.g. numpy/`ml_dtypes` native E4M3 casting). Exponent under/overflow after the shared-exponent adjustment clamps to E4M3's representable range rather than implementing a full IEEE-style subnormal encoding. Real synthesis + PnR is done end-to-end against sky130hd, all 9 PVT corners signed off for the current design; remaining physical gaps are power (OpenROAD's built-in estimate, not vector-based signoff) and fab-readiness (antenna violations remain, DFM signoff explicitly out of scope).

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for full bit-width tables, the gate-level breakdown, the original pipelined design's real numbers, and the identified-but-not-built future directions.
