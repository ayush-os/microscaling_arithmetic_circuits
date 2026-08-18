package epilogue

import chisel3._
import chisel3.util._

/** Stage 1 — Max-finding.
  *
  * Reduction tree over the 32 accumulator elements' magnitudes. Cited
  * from HW2 (phase1_pipeline_spec.md Stage 1, notes.md §1 Part A):
  *   - 31 comparators (k-1)
  *   - 5 cycle latency (log2(k), registered tree depth)
  *
  * Registered between every tree level via an explicit level-by-level
  * fold, each level's output wrapped in `RegNext` before becoming the
  * next level's input — 5 levels for 32 inputs, so this is a real
  * 5-stage pipeline, not one long combinational chain. Keeps each
  * stage's critical path to a single comparator instead of 5 in series.
  *
  * Deliberately not `Vec.reduceTree`'s `layerOp` hook (an earlier version
  * used `reduceTree(op, (x: UInt) => RegNext(x))`, which elaborated with
  * *zero* registers — caught by real synthesis, not simulation). `layerOp`
  * only fires on elements "promoted" past an unpaired leftover at
  * odd-sized levels, plus the final single-element base case; for a
  * power-of-two input like 32, every level divides evenly, so that hook
  * is never invoked at all. Building the tree explicitly here means every
  * level's register is unconditional by construction, not contingent on
  * `reduceTree`'s internal bookkeeping.
  */
class MaxFinder extends Module {
  val io = IO(new Bundle {
    val in  = Input(Vec(32, SInt(20.W)))
    val out = Output(UInt(20.W)) // M: block max magnitude
  })

  // Signed -> magnitude per element first (this is a comparator TREE over
  // magnitudes, not signed values — `Mux(a > b, a, b)` on the raw SInts
  // would pick the greatest signed value, not the greatest magnitude).
  val magnitudes = VecInit(io.in.map(v => Mux(v < 0.S, (-v).asUInt, v.asUInt)))

  var level: Seq[UInt] = magnitudes
  while (level.length > 1) {
    val paired = level.grouped(2).map { case Seq(a, b) => Mux(a > b, a, b) }.toSeq
    level = paired.map(RegNext(_))
  }
  io.out := level.head
}
