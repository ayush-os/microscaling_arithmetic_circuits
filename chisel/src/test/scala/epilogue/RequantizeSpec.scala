package epilogue

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RequantizeSpec extends AnyFreeSpec with ChiselSim with Matchers {

  // Hand-verified concrete cases (not an exhaustive/random sweep against
  // an external golden reference — a natural next step, not done here).
  // io.e is set to (sharedExp + 127) since shared_exp = io.e - 127.
  //
  //  v      sharedExp  expected P_i (byte)                 why
  //  12     0          84  (0_1010_100)                    exact, no rounding: 1.5*2^3=12
  //  25     0          92  (0_1011_100)                    tie, kept mantissa already even -> rounds down to 24
  //  31     0          96  (0_1100_000)                    tie, kept mantissa odd -> rounds up, mantissa overflows into exponent (30->32)
  //  -12    0          212 (1_1010_100)                    sign bit
  //  12     2          68  (0_1000_100)                    shared_exp shifts exponent only, mantissa unchanged (12/4=3)
  //  0      3          0                                    isZero short-circuit, regardless of shared_exp
  val cases = Seq(
    (BigInt(12), 0, BigInt(84)),
    (BigInt(25), 0, BigInt(92)),
    (BigInt(31), 0, BigInt(96)),
    (BigInt(-12), 0, BigInt(212)),
    (BigInt(12), 2, BigInt(68)),
    (BigInt(0), 3, BigInt(0)),
  )

  "Requantize should match hand-verified E4M3 RNE cases" in {
    simulate(new Requantize) { dut =>
      cases.foreach { case (v, sharedExp, expectedP) =>
        dut.io.v(0).poke(v.S(20.W))
        for (i <- 1 until 32) dut.io.v(i).poke(0.S(20.W))
        dut.io.e.poke((sharedExp + 127).U(8.W))
        dut.io.p(0).expect(expectedP.U(8.W))
      }
    }
  }
}
