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
