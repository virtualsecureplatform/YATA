import chisel3._
import chisel3.util._

class ASCONSbox() extends Module{
    val io = IO(new Bundle{
        val in = Input(Vec(5,UInt(64.W)))
        val out = Output(Vec(5,UInt(64.W)))
    })
    // --- substitution layer ---
    val xorwire = Wire(Vec(5,UInt(64.W)))
    xorwire := io.in
    xorwire(0) := io.in(0) ^ io.in(4)
    xorwire(2) := io.in(2) ^ io.in(1)
    xorwire(4) := io.in(4) ^ io.in(3)

    val txorwire = Wire(Vec(5,UInt(64.W)))
    for(i <- 0 until 5){
        // val t = (xorwire((i+1)%5) ^ (0xFFFFFFFFFFFFFFFF).U) & xorwire((i+2)%5)
        val t = ~xorwire((i+1)%5) & xorwire((i+2)%5)
        txorwire(i) := xorwire(i) ^ t
    }
    io.out(0) := txorwire(0) ^ txorwire(4)
    io.out(1) := txorwire(1) ^ txorwire(0)
    io.out(2) := ~txorwire(2)
    io.out(3) := txorwire(3) ^ txorwire(2)
    io.out(4) := txorwire(4)
    // io.out(2) := io.out(2) ^ BigInt(0XFFFFFFFFFFFFFFFF,16).U
}

class ASCONLinear() extends Module{
    val io = IO(new Bundle{
        val in = Input(Vec(5,UInt(64.W)))
        val out = Output(Vec(5,UInt(64.W)))
    })
    io.out(0) := io.in(0) ^ io.in(0).rotateRight(19) ^ io.in(0).rotateRight(28)
    io.out(1) := io.in(1) ^ io.in(1).rotateRight(61) ^ io.in(1).rotateRight(39)
    io.out(2) := io.in(2) ^ io.in(2).rotateRight(1) ^ io.in(2).rotateRight(6)
    io.out(3) := io.in(3) ^ io.in(3).rotateRight(10) ^ io.in(3).rotateRight(17)
    io.out(4) := io.in(4) ^ io.in(4).rotateRight(7) ^ io.in(4).rotateRight(41)
}

class ASCONPermuitation() extends Module {
    val io = IO(new Bundle{
        val in = Input(Vec(5,UInt(64.W)))
        val r = Input(UInt(4.W))
        val out = Output(Vec(5,UInt(64.W)))
    })
    val crtable = Wire(Vec(12,UInt(8.W)))
    for(i <- 0 until 12) yield{
        crtable(i) := (0xf0 - i*0x10 + i*0x1).U
    }
    val sbox = Module(new ASCONSbox)
    val linear = Module(new ASCONLinear)
    sbox.io.in := io.in
    // --- add round constants ---
    sbox.io.in(2) := io.in(2) ^ crtable(io.r)
    linear.io.in := sbox.io.out
    io.out := linear.io.out
}

class ASCONPermuitationNcycle(N:Int) extends Module{
    val io = IO(new Bundle{
        val in = Input(Vec(5,UInt(64.W)))
        val r = Input(UInt(log2Ceil(12/N).W))
        val out = Output(Vec(5,UInt(64.W)))
    })

    val permutations = for(i <- 0 until N) yield {
        Module(new ASCONPermuitation)
    }
    permutations(0).io.in := io.in
    permutations(0).io.r := io.r*N.U;
    for(i <- 0 until N-1){
        permutations(i+1).io.in := permutations(i).io.out
        permutations(i+1).io.r := (io.r*N.U) + (i+1).U
    }
    io.out := permutations(N-1).io.out
}


object ASCONPRNGState extends ChiselEnum {
  val SqueezePermutaion, Squeeze  = Value
}

class ASCONPRNG(N:Int) extends Module{
    val io = IO(new Bundle{
        val seed = Input(UInt(320.W))
        val seedwrite = Input(Bool())
        val axi4sout = new AXI4StreamManager(32)
    })

    val S = Reg(Vec(5,UInt(64.W)))
    val cntreg = RegInit(0.U(log2Ceil(12/N).W))
    val downsizer = Module(new DownSizer(64,32))
    downsizer.io.flush := io.seedwrite
    downsizer.io.axi4sin.TDATA := S(0)
    downsizer.io.axi4sin.TVALID := false.B
    io.axi4sout <> downsizer.io.axi4sout

    val permutationN = Module(new ASCONPermuitationNcycle(N))
    permutationN.io.in := S
    permutationN.io.r := cntreg

    val statereg = RegInit(ASCONPRNGState.SqueezePermutaion)
    switch(statereg){
        is(ASCONPRNGState.SqueezePermutaion){
            cntreg := cntreg + 1.U
            S := permutationN.io.out
            when(cntreg === (12/N-1).U){
                cntreg := 0.U
                downsizer.io.axi4sin.TDATA := permutationN.io.out(0)
                downsizer.io.axi4sin.TVALID := true.B
                when(~downsizer.io.axi4sin.TREADY){
                    statereg := ASCONPRNGState.Squeeze
                }
            }
        }
        is(ASCONPRNGState.Squeeze){            
            downsizer.io.axi4sin.TVALID := true.B
            when(downsizer.io.axi4sin.TREADY){
                cntreg := cntreg + 1.U
                S := permutationN.io.out
                statereg := ASCONPRNGState.SqueezePermutaion
            }
        }
    }
    when(io.seedwrite){
        for(i <- 0 until 5){
            S(i) := io.seed(64*(i+1)-1,64*i)
        }
        cntreg := 0.U
        statereg := ASCONPRNGState.SqueezePermutaion
    }
}