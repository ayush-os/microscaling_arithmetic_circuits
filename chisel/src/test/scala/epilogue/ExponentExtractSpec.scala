package epilogue

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ExponentExtractSpec extends AnyFreeSpec with ChiselSim with Matchers {

  // Already-verified table from notes.md §4 (Phase 0) — not re-derived here.
  val cases = Seq(
    (BigInt(0), 0),           // hardcoded M=0 case
    (BigInt(1), 119),         // smallest nonzero
    (BigInt(200000), 136),    // worked example
    (BigInt(262144), 137),    // boundary, 2^18, theoretical max for N=16/B=8
  )

  "ExponentExtract should match the Phase 0 verification table" in {
    simulate(new ExponentExtract) { dut =>
      cases.foreach { case (m, expectedE) =>
        dut.io.m.poke(m.U(20.W))
        dut.io.e.expect(expectedE.U(8.W))
      }
    }
  }
}
