package epilogue

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

class EpilogueSpec extends AnyFreeSpec with ChiselSim with Matchers {

  // Golden formula, transcribed directly from Phase 0's already-verified
  // pseudocode (notes.md §4) — not re-derived here.
  def goldenE(m: BigInt): BigInt = {
    if (m == 0) BigInt(0)
    else {
      val raw = m.bitLength - 1 // floor(log2(m))
      val x = raw + 119
      if (x > 254) BigInt(254) else BigInt(x)
    }
  }

  "Epilogue's composed pipeline should match independently-computed E, and Requantize run standalone with that E, for random blocks" in {
    val rnd = new Random(7)
    val trials = Seq.fill(10)(Seq.fill(32)(BigInt(20, rnd) - (BigInt(1) << 19)))

    // Expected outputs: E from the independently-transcribed golden
    // formula (not Epilogue's own ExponentExtract instance), and P_i from
    // a standalone Requantize driven directly with that golden E — not
    // Epilogue's internal wiring. This isolates whether Epilogue's
    // *composition* of already-individually-verified stages (in
    // particular the ShiftRegister latency alignment) is correct, rather
    // than re-checking each stage's algorithm a second time.
    var expected: Seq[(BigInt, Seq[BigInt])] = Seq.empty
    simulate(new Requantize) { requantDut =>
      expected = trials.map { vs =>
        val e = goldenE(vs.map(_.abs).max)
        requantDut.io.e.poke(e.U(8.W))
        vs.zipWithIndex.foreach { case (v, i) => requantDut.io.v(i).poke(v.S(20.W)) }
        val ps = (0 until 32).map(i => requantDut.io.p(i).peek().litValue)
        (e, ps)
      }
    }

    simulate(new Epilogue) { epilogueDut =>
      trials.zip(expected).foreach { case (vs, (e, ps)) =>
        vs.zipWithIndex.foreach { case (v, i) => epilogueDut.io.v(i).poke(v.S(20.W)) }
        epilogueDut.clock.step(5)
        epilogueDut.io.e.expect(e.U(8.W))
        ps.zipWithIndex.foreach { case (p, i) => epilogueDut.io.p(i).expect(p.U(8.W)) }
      }
    }
  }

  // Streaming variant of the test above, not just held-constant blocks:
  // one distinct block fed per cycle, back-to-back, checked against the
  // pipeline's real 5-cycle delay as it drains. The held-constant version
  // can't tell a genuinely-pipelined MaxFinder from a combinational one
  // that just happens to still be showing the right answer 5 cycles
  // later — inputs never changed in between, so a stale/misaligned `v`
  // reaching Requantize would go unnoticed. This is the composition-level
  // check for exactly the bug found via real synthesis:
  // `Requantize.io.v := ShiftRegister(io.v, 5)` only stays correctly
  // aligned with `e` if Stage 1 actually takes 5 cycles to produce `e` —
  // with distinct blocks streaming through, a combinational MaxFinder (or
  // any latency/alignment mismatch) would pair the wrong block's `v` with
  // `e`, which this test would catch and the held-constant one
  // structurally cannot.
  "Epilogue should correctly pipeline distinct, back-to-back streaming blocks" in {
    val rnd = new Random(11)
    val numBlocks = 8
    val blocks = Seq.fill(numBlocks)(Seq.fill(32)(BigInt(20, rnd) - (BigInt(1) << 19)))

    var expected: Seq[(BigInt, Seq[BigInt])] = Seq.empty
    simulate(new Requantize) { requantDut =>
      expected = blocks.map { vs =>
        val e = goldenE(vs.map(_.abs).max)
        requantDut.io.e.poke(e.U(8.W))
        vs.zipWithIndex.foreach { case (v, i) => requantDut.io.v(i).poke(v.S(20.W)) }
        val ps = (0 until 32).map(i => requantDut.io.p(i).peek().litValue)
        (e, ps)
      }
    }

    simulate(new Epilogue) { dut =>
      for (i <- 0 until numBlocks + 5) {
        if (i < numBlocks) {
          blocks(i).zipWithIndex.foreach { case (v, j) => dut.io.v(j).poke(v.S(20.W)) }
        }
        dut.clock.step(1)
        val blockIdx = (i + 1) - 5
        if (blockIdx >= 0 && blockIdx < numBlocks) {
          val (e, ps) = expected(blockIdx)
          dut.io.e.expect(e.U(8.W))
          ps.zipWithIndex.foreach { case (p, j) => dut.io.p(j).expect(p.U(8.W)) }
        }
      }
    }
  }
}
