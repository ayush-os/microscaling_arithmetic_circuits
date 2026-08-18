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
    // *composition* of already-individually-verified stages is correct,
    // rather than re-checking each stage's algorithm a second time.
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
        epilogueDut.io.e.expect(e.U(8.W))
        ps.zipWithIndex.foreach { case (p, i) => epilogueDut.io.p(i).expect(p.U(8.W)) }
      }
    }
  }

  // Now purely combinational (see ARCHITECTURE.md's "Design space beyond
  // the 6-cycle constraint") — the old streaming test existed specifically
  // to distinguish a genuinely-5-cycle-pipelined MaxFinder from one that
  // settles fast, by checking that `e`/`p` track the block that entered 5
  // cycles ago, not the block currently on `io.v`. With no pipeline left
  // to distinguish, that test's premise (and the `ShiftRegister`
  // alignment bug it was designed to catch) no longer applies. This
  // replaces it with the composition-level check that does still matter
  // for a combinational design: back-to-back distinct blocks, poked one
  // per cycle with no settling time given, each immediately producing the
  // *same* cycle's correct output — i.e. `io.v` and `io.e`/`io.p` never
  // drift out of alignment, because there's no latency for them to drift
  // across.
  "Epilogue should correctly handle back-to-back distinct blocks with no settling time" in {
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
      blocks.zip(expected).foreach { case (vs, (e, ps)) =>
        vs.zipWithIndex.foreach { case (v, j) => dut.io.v(j).poke(v.S(20.W)) }
        dut.io.e.expect(e.U(8.W))
        ps.zipWithIndex.foreach { case (p, j) => dut.io.p(j).expect(p.U(8.W)) }
        dut.clock.step(1) // advance to the next block; no pipeline drain to wait on
      }
    }
  }
}
