# Dynamic-Scale MX Epilogue

Hardware circuit that computes a microscaling (MXFP8) block's shared E8M0 exponent **dynamically, per 32-element block, from real accumulator data** — not a compile-time constant — then requantizes all 32 elements against it with round-to-nearest-even.

**Chisel 7 / SystemVerilog · Verilator-simulated · MXFP8 (E4M3 elements, E8M0 shared exponent) · k=32 blocks**

- **2.4x smaller write-back**: 640 raw accumulator bits (32× `SInt(20.W)`) → 264 quantized bits (32× 8-bit E4M3 + one 8-bit shared exponent) — a 3% scale-metadata tax over the pure 8-bit payload.
- **6-cycle epilogue, zero-stall at block size 32**: a 31-comparator max-finding tree, genuinely pipelined (one register stage per tree level, not one long combinational chain) at 5 cycles, plus 1 combinational cycle for exponent extraction + requantization — exactly matches the fixed-latency compute pipeline it feeds, by construction, not by luck.
- **5 Verilator-simulated test suites**, including one that composes all three stages and checks the result against an independently-computed golden exponent and a standalone instance of the requantizer — isolating pipeline-composition bugs from each stage's own already-verified logic.

## What this is

The one real missing piece in an MX accelerator epilogue. Max-finding and a static-scale rescale circuit already existed from prior coursework; this project is specifically the gap between them — a genuinely data-dependent circuit that recomputes the shared scale fresh from real data every block, rather than a scale/shift pair chosen once and baked in at compile time. It does not rebuild a MAC array or the max-finder tree's underlying comparator primitive — it reuses both as given and adds only the dynamic-scale computation and requantization logic on top.

## Pipeline

| Stage | Function | Real numbers |
|---|---|---|
| 1. Max-finding | Comparator-tree reduction over 32 signed accumulator elements' magnitudes | 31 comparators, 5-cycle pipeline (register stage per tree level) |
| 2. Exponent extraction | NOR-reduce zero-detect ∥ priority-encoder → `+119` offset adder → high clamp | Combinational, 8-bit E8M0 output |
| 3. Requantization | Per-element normalize + round-to-nearest-even into E4M3, shared exponent applied as a pure exponent-field adjustment | 32 parallel paths, 1 combinational cycle |

## Real RTL vs. paper model

The original design (worked out on paper before any RTL existed) planned Stage 3 as: physically shift each element's magnitude by the shared exponent, then re-normalize the result a second time. Building it for real surfaced a problem — that needs two compounded dynamic shifts, and Chisel's dynamic left-shift grows its result width to cover the worst case it can't rule out at elaboration time, so the intermediate signal balloons.

The actual fix, found while implementing rather than on paper: multiplying or dividing by a power of two only moves an element's *exponent* — it never changes the mantissa bit pattern. So each element normalizes against its own leading bit exactly once, and the shared exponent gets folded in afterward as pure integer arithmetic on the exponent field, not a second shift. Smaller, and it sidesteps the width blowup entirely. Verified algebraically and confirmed against concrete cases (same mantissa bits at `shared_exp=0` and `shared_exp=2` for the same input, exponent field shifted by exactly 2).

## Verification

- Per-stage unit tests: `MaxFinder` (randomized inputs + the `SInt` two's-complement minimum-value edge case), `ExponentExtract` (concrete verification table spanning the zero/boundary/max cases), `Requantize` (hand-computed E4M3 round-to-nearest-even cases, including the mantissa-overflow-into-exponent path).
- End-to-end composition test (`EpilogueSpec`): random 32-element blocks through the full wired pipeline, checked against an independently-transcribed golden exponent formula and a *separately* instantiated `Requantize` module — deliberately not reusing the pipeline's own internal wiring for the expected values, so this actually catches composition/timing bugs (in particular, the latency-alignment delay the raw per-element inputs need once max-finding became a real multi-cycle pipeline) rather than re-checking each stage's algorithm a second time.

**Known gaps, stated rather than hidden**: no broad randomized/fuzz sweep against an independent external reference (e.g. numpy/`ml_dtypes` native E4M3 casting) — current coverage is hand-computed and formula-derived cases, not exhaustive. Exponent under/overflow after the shared-exponent adjustment clamps to E4M3's representable range rather than implementing a full IEEE-style subnormal encoding. No synthesis has been run — all numbers above are from RTL simulation, not a synthesized netlist.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for full bit-width tables, the gate-level breakdown, and the design tradeoffs behind each stage.
