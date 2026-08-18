package epilogue

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

class MaxFinderSpec extends AnyFreeSpec with ChiselSim with Matchers {

  // Trivial reference — not the interesting part, just a magnitude-max
  // over the same 32 signed values MaxFinder sees.
  def goldenMax(vs: Seq[BigInt]): BigInt = vs.map(_.abs).max

  "MaxFinder should return the max magnitude of 32 signed inputs" in {
    val rnd = new Random(42)
    simulate(new MaxFinder) { dut =>
      for (_ <- 0 until 20) {
        // SInt(20.W) range: [-2^19, 2^19 - 1]
        val vs = Seq.fill(32)(BigInt(20, rnd) - (BigInt(1) << 19))
        vs.zipWithIndex.foreach { case (v, i) => dut.io.in(i).poke(v.S(20.W)) }
        dut.clock.step(5) // Stage 1's declared 5-cycle tree latency
        dut.io.out.expect(goldenMax(vs).U)
      }
    }
  }

  // Real distinguishing test, not just a latency-count check: pokes block
  // A, lets exactly one clock edge pass (enough for level-1's registers to
  // latch A-derived comparisons), then switches the input to a very
  // different block B for the remaining cycles. A purely combinational
  // design would track *current* input and report B's max; a genuinely
  // pipelined one already has A's data latched into level-1 and just
  // needs 4 more edges to walk it through levels 2-5, regardless of what
  // B does at the input in the meantime. This is exactly the check that
  // would have caught MaxFinder's earlier all-combinational elaboration
  // (found via real synthesis, not sim) — the original `step(5)`-then-check
  // tests couldn't distinguish the two because they hold input constant
  // across the whole window.
  "MaxFinder should genuinely pipeline data across cycles, not just settle fast" in {
    simulate(new MaxFinder) { dut =>
      val vsA = Seq.fill(32)(BigInt(1)) // magnitude 1
      val vsB = Seq.fill(32)(BigInt(-524288)) // magnitude 524288, deliberately far from A

      vsA.zipWithIndex.foreach { case (v, i) => dut.io.in(i).poke(v.S(20.W)) }
      dut.clock.step(1) // level-1 registers latch A-derived comparisons here

      vsB.zipWithIndex.foreach { case (v, i) => dut.io.in(i).poke(v.S(20.W)) }
      dut.clock.step(4) // A's data walks through levels 2-5; B never reaches level-1's registers within this window

      dut.io.out.expect(goldenMax(vsA).U) // NOT goldenMax(vsB) — would be 524288 if combinational
    }
  }

  // Edge case: -524288 (-2^19) is SInt(20.W)'s minimum value, where
  // two's-complement negation overflows (its bit pattern is its own
  // negation). Confirms (-v).asUInt still recovers the correct magnitude
  // 524288 here rather than silently producing garbage.
  "MaxFinder should handle the minimum SInt(20.W) value's magnitude correctly" in {
    simulate(new MaxFinder) { dut =>
      val vs = Seq.fill(31)(BigInt(1)) :+ BigInt(-524288) // 31 filler + the edge case, 32 total
      vs.zipWithIndex.foreach { case (v, i) => dut.io.in(i).poke(v.S(20.W)) }
      dut.clock.step(5)
      dut.io.out.expect(BigInt(524288).U)
    }
  }
}
