import chisel3._
import chisel3.util._

class xoshiro128() extends Module{
    val io = IO(new Bundle{
        val seed = Input(UInt(128.W))
        val seedwrite = Input(Bool())
        val axi4sout = new AXI4StreamManager(32)
    })

    val s = Reg(Vec(4,UInt(32.W)))
    val enablereg = RegInit(false.B)
    io.axi4sout.TVALID := enablereg
    io.axi4sout.TDATA := ((s(1)*5.U)(31,0)).rotateLeft(7)*9.U
    when(io.axi4sout.TREADY){
        val t = (s(1) << 9)(31,0)
        s(0) := s(0) ^ s(1) ^ s(3)
        s(1) := s(0) ^ s(1) ^ s(2)
        s(2) := s(0) ^ s(2) ^ t
        s(3) := (s(1) ^ s(3)).rotateLeft(11)
    }

    when(io.seedwrite){
        for(i <- 0 until 4){
            s(i) := io.seed(32*(i+1)-1,32*i)
            enablereg := true.B
        }
    }
}

