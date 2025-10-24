// Arithmetics over P
import chisel3._
import chisel3.util._
import scala.math.pow

class INTorusArithmeticPort(implicit val conf:Config) extends Bundle {
    val A = Input(SInt(conf.wordbits.W))
    val B = Input(SInt(conf.wordbits.W))
    val Y = Output(SInt(conf.wordbits.W))
}

class INTorusADD(implicit val conf:Config) extends Module{
    val io = IO(new INTorusArithmeticPort)

    val sum = RegNext(io.A +& io.B)
    io.Y := Mux(sum>=conf.P.S,sum-conf.P.S, Mux(sum <= -conf.P.S, sum + conf.P.S, sum))
}

class INTorusSUB(implicit val conf:Config) extends Module{
    val io = IO(new INTorusArithmeticPort)
    val sub = RegNext(io.A -& io.B)
    io.Y := Mux(sub>=conf.P.S,sub-conf.P.S, Mux(sub <= -conf.P.S, sub + conf.P.S, sub))
}

class INTorusSREDC(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
        val A = Input(SInt((2*conf.wordbits).W))
        val Y = Output(SInt((conf.wordbits).W))
    })
    val a0 = io.A(conf.wordbits-1,0)
    val a1 = RegNext(io.A(2*conf.wordbits-1,conf.wordbits).asSInt)
    val m = (-((a0 * conf.K.S) << conf.shiftamount) + Cat(0.U(1.W),a0).asSInt)(conf.wordbits-1,0).asSInt
    val t1 = RegNext((((m * conf.K.S) << conf.shiftamount) + m)>>conf.wordbits)
    io.Y := a1-t1
}

class INTorusMULSREDC(implicit val conf:Config) extends Module{
    val io = IO(new INTorusArithmeticPort)
    val sredc = Module(new INTorusSREDC)
    sredc.io.A := ShiftRegister(io.A * io.B,conf.multiplierpipestage)
    io.Y := sredc.io.Y
}

class INTorusConstTwiddleMul(val radixbit: Int, val num: Int, implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
        val A = Input(SInt((conf.wordbits+1).W))
        val Y = Output(SInt((2*conf.wordbits).W))
    })
    io.Y := RegNext(io.A*(pow(conf.rk,num*(conf.radixs2>>(radixbit-1))).toInt.S))<<(num*(conf.shiftamount>>(radixbit-1)))
}