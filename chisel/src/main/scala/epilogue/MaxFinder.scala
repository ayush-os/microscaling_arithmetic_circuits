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
  * Registered between every tree level via reduceTree's `layerOp` hook
  * (fires once per level, not once per comparator) — 5 levels for 32
  * inputs, so this is a real 5-stage pipeline, not one long combinational
  * chain. Keeps each stage's critical path to a single comparator instead
  * of 5 in series.
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
  io.out := magnitudes.reduceTree(
    (a: UInt, b: UInt) => Mux(a > b, a, b),
    (x: UInt) => RegNext(x),
  )
}
