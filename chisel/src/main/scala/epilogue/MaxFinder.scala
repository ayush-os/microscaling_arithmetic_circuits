package epilogue

import chisel3._
import chisel3.util._

/** Stage 1 — Max-finding.
  *
  * Reduction tree over the 32 accumulator elements' magnitudes: 31
  * comparators (`k-1`), 5 levels deep (`log2(k)`). Purely combinational —
  * see `ARCHITECTURE.md`'s "Design space beyond the 6-cycle constraint"
  * section for why. The tree previously registered between every level
  * (`RegNext` per level, giving a genuine 5-cycle pipeline) to match an
  * external fixed-latency pipeline this block no longer targets; that
  * registering has been removed, and the level-by-level fold below is now
  * one unbroken combinational chain from `io.in` to `io.out`.
  *
  * Built as an explicit level-by-level fold rather than
  * `Vec.reduceTree`'s `layerOp` hook — carried over from when this
  * mattered for correctness (an earlier `reduceTree(op, (x: UInt) =>
  * RegNext(x))` silently elaborated with zero registers on this
  * power-of-two-sized input — see `ARCHITECTURE.md`'s "The MaxFinder bug,
  * found via real synthesis"). No longer a correctness concern now that
  * there's nothing to register, but kept for continuity with the rest of
  * the module.
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
    level = level.grouped(2).map { case Seq(a, b) => Mux(a > b, a, b) }.toSeq
  }
  io.out := level.head
}
