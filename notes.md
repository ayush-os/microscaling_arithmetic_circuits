# Phase 0 Working Notes — Dynamic Block-Shared-Exponent Circuit

Chronological/process record of Phase 0: the required reading, the real
undelegated circuit decision (max-magnitude → E8M0 exponent), every wrong
turn and correction made getting there, and the final verified circuit.
This is the raw process log — see `handoff.md` for the condensed
state-of-play a new session should actually start from.

---

## 0. Where "#1" and "#7" actually point

The spec repeatedly cites "#1/#7's toolchain" and "#1/#7's real
`SInt(20.W)` accumulator." These are **not** course materials — they're
the author's own prior projects, both real, both RTL-grounded:

- **#1 = `vliw-isa-and-scheduler`** — a hand-scheduled VLIW ISA built
  directly against real Gemmini RTL behavior (weight shadow registers in
  `PE.scala`, concurrent ports in `MeshWithDelays`).
- **#7 = `workload-to-silicon`** — prefill-attention roofline → hardware
  hypothesis → Timeloop → real Gemmini RTL validation. This is where the
  `SInt(20.W)` accumulator number actually comes from: reading Gemmini's
  *generated* RTL for `AttentionPrefillRocketConfig` directly showed
  `inputType = SInt(8.W)`, `spatialArrayOutputType = SInt(20.W)` —
  confirmed against real generated signal declarations, not assumed.

Both use Chisel/Gemmini/Verilator (Chipyard, Farmshare) — that's the
"#1/#7's toolchain" the spec chose over Lab 1's Catapult/AWS F2 path,
since AWS access is no longer available.

**Caution carried forward**: `SInt(20.W)` is real hardware precedent, but
it was sized for Gemmini's *own* MAC pipeline (8-bit inputs, whatever
reduction depth Gemmini's real workloads use) — not derived for *this*
project's specific block size/element precision. Treated as "a real
number to anchor against," not "the number this project must inherit."
(Resolved concretely in §3 below — turns out N=16 makes it match exactly,
by choice, not by necessity.)

---

## 1. Phase 0 required reading — TL;DRs

### OCP MX Formats (via companion paper arXiv:2310.10537, quoting the
spec's own §6.2/§6.3 — the spec itself sits behind Cloudflare, inaccessible
directly)

- **The exponent formula, spec-exact** (Algorithm 1, cites OCP §6.3):
  ```
  shared_exp ← ⌊log2(max_i |V_i|)⌋ − emax_elem
  X ← 2^shared_exp
  ```
  `emax_elem` = the largest normal exponent the *element* format (E4M3,
  E2M1, etc.) can represent. Not just "exponent of the max" — offset by
  the element format's own ceiling so the block's range is centered
  against what the narrow element format can actually hold.
- **Critical structural finding**: §6.2's "efficient dot product" outputs
  a **scalar float** (BFloat16/FP32), *not* a new MX block. Requantizing
  back into MX is a separate operation (§6.3), reapplied fresh to the
  output. The spec does not fuse accumulate+requantize into one
  primitive — this project's epilogue *is* that reapplication, specialized
  to sit right after a PE array's accumulator. Directly validates the
  project's scope claim that this is a real, missing piece.
- **All-zero-block handling**, from the real reference implementation
  (`microsoft/microxcaling`, `_shared_exponents`):
  `shared_exp = floor(log2(max_abs + FP32_MIN_NORMAL·(max_abs==0)))`,
  clamped to `[-emax, emax]`. A software-side epsilon trick to dodge
  `log2(0)` — informed, but not directly portable to RTL (see §3).

### AdaptivFloat (Tambe et al., arXiv:1909.13271 — full paper read)

- **Algorithm 2's mechanism**:
  `exp_max := normalized exponent s.t. 2^exp_max ≤ max(|W|) < 2^(exp_max+1)`,
  `exp_bias := exp_max − (2^e − 1)`. Same two-step shape as MX (raw
  floor-log2, then a bias correction) — independent confirmation this is
  the right *kind* of answer, not an MX-specific quirk.
- **Granularity contrast**: recomputed once per **layer/tensor**, not per
  32-element block — the real middle point between Lab 1's never-recompute
  and MX's every-32-elements.
- **Zero handling, a genuinely different design choice**: AdaptivFloat
  sacrifices `±min` to encode `±0` instead of using denormals (Fig. 2)  —
  a different project's answer to the same all-zero problem, worth one
  contrast sentence in the writeup.

### Lab 1 Task 2 (`RunScale`) — the explicit contrast case

`(accumulator * scale) >> right_shift` approximates division by 12.25.
`kAccumScale`/`kAccumShift` are two constants found by hand/sweep
*offline*, baked into RTL at HLS time. One scale/shift pair for the
*entire* design, no data-dependence, no recompute-on-new-block logic —
this is what static ("constant, chosen once, no runtime computation")
actually looks like next to MX's per-block dynamic recompute.

### HW2 (`Homework 2.pdf`, now in-repo)

MXFP8 accelerator, E4M3 elements + E8M0 scale, k=32.

- **Part A**: minimum-latency max-finder = `k−1=31` comparators, tree
  depth `log₂32=5` cycles. 128-wide vector at block size 32 → **4
  independent MX blocks** (not one shared scale across 128). Total
  epilogue latency (max-find + 1 cycle for scale/shift/round) = **6
  cycles**. *(Precision note: `log₂k` is max-finding depth alone; `log₂k+1`
  is total epilogue latency — the spec's own summary line conflates these,
  worth being exact about which one Phase 2's RTL check verifies.)*
- **Part B**: prologue+compute latency, fixed regardless of k: SRAM read
  (2) + Dequant (3) + FMA (1) = **6 cycles**.
- **Part C.a — real, load-bearing number**: comparing epilogue latency
  (`log₂k+1`) against the fixed 6-cycle compute pipeline across k:
  k=8→4, k=16→5, k=32→6 (**exactly balanced**), k=64→7 (**1-cycle stall,
  real crossover point**). k=32 isn't just "the standard choice" — it's
  the largest block size this pipeline doesn't stall on.
- Memory overhead (scale tax): k=8→12.5%, k=32→3.0%, k=64→1.5%.

---

## 2. Element format decision

**MXFP8, E4M3 elements.** Chosen explicitly for signal-to-noise/time,
not "the obvious default":

- Matches HW2's own problem statement exactly — zero new format-reading,
  every HW2 number stays directly reusable with no re-derivation.
- Best-documented variant across every source read (OCP paper's own
  Table 1, accuracy tables, the NVIDIA FP8 blog HW2 itself recommends).
- Narrower formats (MXFP4/MXFP6) only buy more clamp/saturation edge-case
  surface — no new insight into the actual mechanism being designed.

`emax_elem = 8` — E4M3's largest normal magnitude is `1.75 × 2⁸`
(bias 7), so 8 is the exponent ceiling used in the offset below.

---

## 3. Sizing the accumulator: B, N, k, and where they each come from

**The recurring confusion, resolved**: `k` (MX block size, =32, spec-fixed)
and `N` (this PE's own reduction/dot-product depth, a free implementation
choice) are unrelated numbers that don't need to match. `k` = how many
*finished* accumulator outputs share one exponent. `N` = how many terms
get summed *inside* one of those outputs before it's finished. No
"convert N numbers into k numbers" step exists regardless of N's value —
the epilogue only ever sees k=32 already-finished numbers.

- **B = 8** (MAC input width) — matches #7's real `SInt(8.W)` Gemmini
  precedent.
- **N = 16** (reduction depth per accumulator output) — a deliberate
  choice, not forced: picked *specifically* so the resulting accumulator
  width matches #7's real, already-cited `SInt(20.W)` exactly, rather
  than inventing an unmotivated number. (User's first instinct was N=k=32
  for simplicity/to avoid a perceived "N→k conversion step" — that step
  turned out not to exist either way, so N=16 was chosen instead on
  stronger grounds: exact match to real, already-verified hardware.)
- **Sizing rule used**: `accumulator width ≈ 2B + ⌈log₂N⌉`. With B=8,
  N=16: `16 + 4 = 20` bits — exactly `SInt(20.W)`, zero divergence from #7
  to explain.
- **k = 32**, fixed by the MX spec, not a choice.

**Worked example used to sanity-check the whole pipeline** (A, B both
32×16 tensors; row_i(A)·row_i(B) for i=0..31 is one valid way to produce
32 independent length-16 reductions, i.e. k=32 outputs each with N=16):
worst-case magnitude per accumulator = `16 × 128 × 128 = 262,144 = 2^18`,
comfortably inside 20-bit signed range (max magnitude `2^19−1=524,287`).
*(An earlier illustrative example used M=431,000 for the block's max —
this is actually unreachable for N=16/B=8, exceeding the real 262,144
ceiling. Caught and corrected before it propagated into the final
formula check — see §4.)*

---

## 4. The Phase 0 circuit: max-magnitude M → E8M0 exponent E

### Final, verified circuit

```
if M == 0:
    return 0
raw = leading_zero_count(M)      # = floor(log2(M)), via priority-encoder
x = raw + 119                    # 119 = 127 (E8M0 bias) − 8 (emax_elem)
if x > 254: x = 254              # clamp high; 255 is reserved for NaN
return x
```

### How it was derived, including the real mistakes caught along the way

1. **Mechanism**: `floor(log2(M))` in hardware is a leading-zero-counter/
   priority-encoder finding M's highest set bit position — no actual log
   circuit needed. Confirmed as the right shape by three independent
   sources (MX, AdaptivFloat, and the reference implementation) all doing
   "raw floor-log2, then a bias correction," never a bare floor-log2 alone.

2. **The `M == 0` hardcode**: doesn't affect output correctness at all —
   if M=0 every element in the block is 0, and 0 × any scale = 0
   regardless of what E is. Only needs to avoid E8M0's reserved all-ones
   NaN pattern, which `0` trivially does. Decided directly (delegated,
   not a real open question) rather than agonized over.

3. **One offset, not two**: the element-format alignment (`−emax_elem`)
   and E8M0's own storage bias (`+127`) are both compile-time constants —
   folded into a single adder constant rather than two hardware stages.
   `combined_offset = 127 − emax_elem = 127 − 8 = 119`.

4. **A real sign bug, caught by working a concrete example**: an earlier
   draft wrote `x = floor(log2(M)) − correction`. Plugging in a
   (mistakenly invalid, see below) M=431,000 gave `18 − 119 = −101`,
   which would clamp *every* block to the same minimum exponent regardless
   of data — obviously broken. Root-caused by re-deriving the two-step
   version separately (`− emax_elem` then `+ 127`) and finding they
   disagreed with the "one combined subtraction" version — the correct
   combined operation is **addition** (`+119`), not subtraction. This
   was a live example of exactly the "gap-hunting is the highest-value
   activity" pattern from prior projects: the walkthrough's job was to
   surface exactly this kind of error before it reached RTL.

5. **The invalid M example, also caught in the same pass**: M=431,000
   exceeds N=16/B=8's real worst-case ceiling of 262,144 — the number was
   never reachable by this configuration in the first place. Re-verified
   the corrected formula against a valid M=200,000: `floor(log2(200000))
   = 17` (since `2^17=131,072 ≤ 200,000 < 262,144=2^18`), `x = 17+119 =
   136` — sane, mid-range, no clamping triggered.

6. **The low clamp (`x < 0`) is provably dead code for this design** —
   M is always a nonnegative integer count when nonzero (never a
   fraction, since it comes straight from a fixed-point accumulator), so
   `raw ≥ 0` always, so `x ≥ 119` always. Kept in the circuit for
   robustness/generality, but explicitly noted as unreachable given this
   project's actual numeric range — the low clamp only matters when
   encoding real values that can be smaller than 1, which a raw integer
   accumulator output never is.

7. **A real, unforced observation**: E8M0's full 8-bit range (0–254) is
   barely touched by this design — M's bounded range (0 to `2^18`) means
   `x` only ever lands in roughly [119, 137]. Not a bug — a genuine
   consequence of a fixed-point accumulator's limited dynamic range
   compared to what E8M0's 8 bits were built to support. Worth a sentence
   in the Phase 1/4 writeup, not something that needs fixing.

### Verification table

| M | Valid for N=16,B=8? | floor(log2 M) | x = floor+119 | Notes |
|---|---|---|---|---|
| 0 | yes | — | 0 | hardcoded, correctness-irrelevant |
| 1 | yes | 0 | 119 | smallest nonzero case |
| 200,000 | yes | 17 | 136 | worked example, sane |
| 262,144 (2^18) | boundary | 18 | 137 | theoretical max for this config |
| 431,000 | **no** | (18) | (137, coincidentally same as above) | unreachable — caught as invalid test input |

---

## 5. Phase 0 checkpoint — met

Per the spec's own checkpoint language ("the exponent-extraction
mechanism derived and stated concretely, ready to become real RTL"):
input representation (M, 20-bit unsigned magnitude from the accumulator),
output representation (8-bit E8M0), the leading-zero-counter's role, the
combined offset constant (119, with its derivation), and both edge cases
(M=0, high clamp with a proven-dead low clamp) are all pinned down with
verified concrete numbers — not left as "basically what MX does."

---

## 6. Open threads carried into Phase 1

- **Requantization stage, entirely undesigned**: given X=2^shared_exp,
  shift+round all 32 raw accumulator values into E4M3. Real open
  question: one shared barrel shifter reused 32× (smaller, slower) vs.
  32 parallel shifters (larger, one-cycle) — an area/latency tradeoff to
  actually work out, not assume. Rounding mode (round-to-nearest-even vs.
  truncation) also undecided — reuse whatever reading #5's rounding-mode
  discussion says once revisited.
- **The real accumulator-precision check Phase 1 explicitly asks for**:
  done implicitly in §3 above (N=16 chosen so 20 bits exactly matches #7)
  — but Phase 1's deliverable should state this explicitly as its own
  checked item, not just inherit it from these notes.
- **Phase 1's deliverable**: all three stages (max-finding — cite HW2
  directly, no new derivation; exponent extraction — this document;
  requantization — not started) written up as one coherent pipeline spec,
  bit widths and latency/area tradeoffs stated with one chosen, ready to
  hand to Phase 2 for actual RTL.

---

## 7. Phase 1 working notes — requantization, the two real decisions

Process log for closing out the two open threads from §6. Final writeup
lives in `phase1_pipeline_spec.md`; this section is the reasoning trail.

### 7.1 The requantization formula

Mirrors OCP MX's own quantize direction (Algorithm 1 gave
`shared_exp ← ⌊log2(max)⌋ − emax_elem`, `X ← 2^shared_exp`, for decode;
the quantize side reapplies the same `X`):

```
shared_exp = E − 127                          # unbias E8M0 (E from Stage 2)
P_i = Q_E4M3(V_i × 2^(−shared_exp))           for i = 0..31
```

Real detail worth keeping: since `X` is always a power of two, `V_i / X`
is a pure signed bit-shift, never a real divider — right-shift when
`shared_exp > 0`, left-shift when `shared_exp < 0` (verified range
roughly [−8, +10], genuinely bidirectional, not just a right-shift).
`Q_E4M3(·)` is where rounding actually happens — a per-element LZC
re-normalizes the shifted magnitude, keeping the top 3 bits as mantissa.
`V_i` is signed (`SInt(20.W)`), so sign strips off before the
shift/mantissa-extraction and reattaches after.

### 7.2 Serial vs. parallel shifter — decided: parallel

Initial instinct was "parallel is just 32x the area, obviously" — true
as a first-order number for the shifter datapath itself, but two
refinements mattered:

- **Latency, not area, is what actually forces the decision.** HW2's
  own model budgets exactly 1 cycle for "scale/shift/round" inside the
  6-cycle total epilogue latency that makes k=32 the largest block size
  that doesn't stall the fixed 6-cycle compute pipeline (§1, Part C.a).
  Serial's 32 cycles would blow that to ~37, breaking the very
  crossover finding this project's k=32 choice rests on. So parallel
  isn't "chosen for being faster" — it's close to mandatory given a
  constraint already established in Phase 0.
- **The area ratio isn't a clean 32x either direction.** The
  shift-amount decode is one shared value (`shared_exp`, one per
  block) broadcast to all 32 data paths — true under *either*
  architecture, since serial also only decodes it once and reuses the
  same select lines across 32 cycles. Not a parallel-specific saving,
  a wash. What *does* differ: serial must add control parallel never
  needs at all — a 32:1 input mux, a cycle counter/sequencer, and
  output demux/routing, purely to time-multiplex one physical shifter.
  Parallel needs none of that, not because it "amortizes control
  better," but because there's nothing to schedule in the first place.
  Net effect: serial's true cost is understated by "just 1 shifter."

**Decided: parallel** (32 shifters, 1 cycle), on the latency constraint
primarily, with the area cost stated honestly (~32x shifter datapath,
partially offset by serial's own control tax) rather than assumed away.

### 7.3 Rounding mode — decided: round-to-nearest-even

Not a close call, low deliberation needed:

- Truncation is a biased estimator (always toward zero) — compounds
  directionally across every block/layer, the accuracy-drift failure
  mode a quantized pipeline can't afford.
- RNE is the default in every real format read during Phase 0 (NVIDIA
  FP8/E4M3, bfloat16, MX itself) — established convention, not an
  outlier pick.
- Hardware cost is small and doesn't change the decision: guard bit +
  sticky-OR + tie-break-driven +1 increment, a handful of gates next to
  the barrel shifter/LZC already in the design.

(Note: `notes.md`'s own §6 flagged "reuse whatever reading #5's
rounding-mode discussion says" — that source was never identified/
available this session, unlike #1/#7 which were pinned down in §0. The
reasoning above stands independently of it; flagged rather than
silently assumed resolved, in case #5 surfaces later with a different
angle.)

### 7.4 Accumulator-precision check — restated as its own explicit item

Per Phase 1's own requirement (state it explicitly, don't just inherit
it from §3): worst-case accumulator magnitude `16×128×128=262,144=2^18`
fits comfortably inside the 20-bit signed range (`2^19−1=524,287`, 19 of
20 bits used at the ceiling), and Stage 2's own verified output range
([119,137], a real spread) confirms this isn't degenerate downstream.
Sufficient by construction (N=16 chosen for exactly this), not luck —
full statement in `phase1_pipeline_spec.md`.

### 7.5 Phase 1 checkpoint — met

All three stages (max-finding cited, exponent extraction slotted in,
requantization now fully designed: formula + parallel-shifter decision
+ RNE decision) plus the explicit accumulator check are written up
together in `phase1_pipeline_spec.md`, with real bit widths and real
latency/area tradeoffs stated and one chosen throughout — ready to
become Phase 2's RTL.
