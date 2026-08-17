# Project Spec: The Microscaling Epilogue — Dynamic Block-Shared-Exponent Circuit

**Continuity note, and why this project shrank.** Originally scoped as a
full MAC+epilogue design. CS217's Labs 1–3 turned out to already cover most
of that ground, hands-on and taken further than this project originally
asked for real synthesis area reports (Catapult's "Total Area Score"),
real AWS F2 FPGA deployment. **Lab 1** built a real quantized MAC with a
real rescale circuit (scale+shift approximating division, tuned to <0.2%
error). **Lab 3** built a real comparator-tree max-finder (for Softmax) in
verified RTL. Both real, both count.

**What survives, precisely — not "MX formats" broadly, one specific
mechanism.** Lab 1's scale/shift is a *single, fixed, compile-time-tuned*
constant pair — chosen once, baked in, no runtime computation needed to
produce it, only to apply it. Microscaling's actual defining feature is
different in kind: the shared E8M0 exponent is *recomputed fresh, from
real data, every block of 32 elements* — a genuine data-dependent circuit
in the write-back path, not a constant. Lab 3's max-finder is the right
building block but was never wired into an exponent-computation-and-
requantization pipeline; it answered "which element is loudest," not "what
should the new shared scale be, and how do we rescale all 32 elements
against it." **That gap — dynamic scale computation, not static — is the
entire scope of this project now.**

**Toolchain: SystemVerilog/Chisel/Verilator** (#1/#7's toolchain, chosen
over Catapult/AWS F2 since AWS access is no longer available). HW2's own
real, correct analytical predictions (max-finding latency = log₂(k)+1,
comparator count = k−1) remain the real target this project's RTL gets
checked against — reused directly, not re-derived.

**Legend:** 🔧 = boilerplate/setup. 🧠 = your job.

---

## Phase 0 — Setup (🔧, one real 🧠 decision)

### Reading (🔧)

- **OCP MX Formats spec v1.0** (already read for HW2) — re-read §6's
  dot-product operation specifically for how the *new* output scale gets
  derived after accumulation, not just how an existing input scale is
  applied.
- **Lab 1's Task 2, re-read as the explicit contrast case** — what made
  that circuit simple (one constant, chosen once, no data-dependent logic)
  is exactly what this project's circuit can't have.
- **AdaptivFloat** (Tambe et al., cited in Lab 3's own references — same
  course instructor, genuinely relevant): a different scaling-granularity
  idea (per-tensor adaptive exponent, dynamically recomputed) — a real,
  useful middle point between Lab 1's fully-static scale and MX's fully-
  per-block-dynamic one. Read for how someone else solved "recompute the
  scale from real data, in hardware."

### 🧠 Decision: max-magnitude → E8M0 exponent, the one real undelegated
circuit question

HW2 already gives you the max-finding tree's latency and comparator count.
What it doesn't give you: the actual logic that turns "the block's max
absolute value is M" into "the correct E8M0 exponent is E." Derive this —
likely a leading-zero-count/priority-encoder operation on the max value's
own exponent field, but work out the real mechanism and edge cases
(all-zero block, subnormal handling) rather than assuming it's trivial.

**Checkpoint:** the exponent-extraction mechanism derived and stated
concretely, ready to become real RTL.

---

## Phase 1 — Derive the full epilogue pipeline (🧠)

Three real pieces, building on Phase 0's decision:

1. **Max-finding** — reuse HW2's own latency/comparator-count formulas
   directly (log₂(k)+1, k−1). Don't re-derive; cite and build to it.
2. **Exponent extraction** — Phase 0's decision, now spelled out as a real
   circuit (bit widths, the priority-encoder/leading-zero-count structure).
3. **Requantization** — given the new shared exponent, all 32 elements
   need re-shifting/rounding against it. Real design question, directly
   analogous to HW2's own block-size sweep: one shared barrel shifter,
   serially reused across 32 elements (smaller, slower), or 32 parallel
   shifters (larger, one-cycle)? Derive the real area/latency tradeoff,
   don't assume an answer — reuse #5's rounding-mode discussion (round-to-
   nearest-even vs. truncation) for the actual rounding decision.

**Real check against the accumulator's own established precision**: does
#1/#7's real `SInt(20.W)` accumulator carry enough bits for max-finding to
be meaningful at this stage, or does something need adjusting? A concrete
check, not assumed fine.

**Deliverable**: a full, written pipeline spec (three stages, real bit
widths, real latency/area tradeoffs stated and one chosen) ready for RTL.

---

## Phase 2 — Real RTL (🔧 build, 🧠 design)

Build the three-stage pipeline from Phase 1 in real SystemVerilog/Chisel,
Verilator-simulated, matching #1/#7's toolchain:

- Max-finding reduction tree — real comparator instances, real tree
  structure. **Verify latency and comparator count exactly match HW2's
  own predictions** — a genuine "does the pencil-and-paper prediction
  survive contact with real RTL" check, the same discipline every prior
  project has applied to its own hand-derivations.
- Exponent-extraction logic, per Phase 0/1's derivation.
- Requantization hardware, per Phase 1's chosen serial-vs-parallel design.
- **End-to-end verification** against a golden Python/numpy reference
  across real, varied input blocks (not one test case) — the same
  "brute-force where it's small enough, golden-model everywhere else"
  discipline #7's own MAC verification used.
- **Integration point, stated concretely**: how this epilogue attaches
  after #1/#7's PE array's accumulator output — the point where Lab 1's
  static-scale approach would have done something simpler, and doesn't
  anymore.

**Deliverable**: real, simulated, verified RTL for the full dynamic-scale
epilogue.

---

## Phase 3 — Real synthesis, validated against HW2's sweep (🧠)

**Yosys + an open PDK (e.g. sky130)** — no AWS needed, same "real, freely
available tool" discipline as #1's own Verilator usage.

**The real validation this phase exists for**: re-run HW2's own block-size
sweep (k=8/16/32/64) against your *real* synthesized circuit and check it
against HW2's analytical predictions. Does real synthesis confirm the
idealized comparator-count model, or does something (real gate delay,
real routing) diverge?

**The real comparison**: this dynamic-scale epilogue's real area/latency
cost versus Lab 1's static-scale circuit — a genuine, sharp, well-grounded
number now, since both are real, hands-on-built reference points (one from
coursework, one from this project) rather than one real and one estimated.

---

## Phase 4 — Synthesis (🧠)

- **The real arc worth naming explicitly**: kernel-level MXFP8 experience
  (Rivos) → a real static-scale quantization circuit (Lab 1) → a real
  dynamic per-block-scale circuit (this project). That's a genuinely
  complete, honest progression through low-precision numerics hardware,
  not three disconnected data points.
- **Direct relevance to MatX/Etched**, stated plainly: real RTL, real
  synthesized area/latency numbers, for the exact current-standard format
  (MXFP4/MXFP8) both companies' postings name.
- **State the scope boundary explicitly**: this project does not rebuild
  a MAC unit — it reuses #1/#7's and Lab 1's existing designs and adds the
  one real, narrow, missing piece. Say that plainly rather than implying
  broader scope than what was actually built.

---

## Note on scope

Resist rebuilding what Labs 1–3 already did (a general MAC, a static
rescale circuit, a max-finder for Softmax) — reuse them as given
components and real reference points. Resist extending into training-
precision formats or a full systolic-array integration — this stays a
single, well-scoped circuit: the dynamic-scale epilogue, nothing more.

## Fallback

Phase 2 (real, verified RTL for the three-stage epilogue) is a complete
artifact on its own — Phase 3's synthesis-based validation is a real
addition, not a prerequisite.
