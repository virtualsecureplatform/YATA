import chisel3._
import chisel3.util._

class DoubleLbuffer(val size: Int, val width:Int , val delay:Int) extends Module{
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(width.W)))
        val out = Output(Vec(size,SInt(width.W)))
        val validin = Input(Bool())
        val validout = Output(Bool())
    })
    val validregarray = Reg(Vec(size,Bool()))
    val columnregarray = Reg(Vec(size-1,Vec(size,SInt(width.W))))
    val rowregarray = Reg(Vec(size,Vec(size,SInt(width.W))))

    io.validout := validregarray(0)
    io.out := rowregarray(0)

    validregarray(size-1) := false.B
    columnregarray(size-2) := io.in
    for(i <- 0 until size){
        rowregarray(size-1)(i) := 0.S
    }
    for(i <- 0 until size-1){
        validregarray(i) := validregarray(i+1)
        rowregarray(i) := rowregarray(i+1)
    }
    for(i <- 0 until size-2){
        columnregarray(i) := columnregarray(i+1)
    }

    val cntreg = RegInit(0.U(log2Ceil(size).W))
    when(io.validin){
        cntreg := cntreg + 1.U
        when(cntreg === (size-1).U){
            cntreg := 0.U
        }
    }.otherwise{
        cntreg := 0.U
    }
    when(ShiftRegister(io.validin & (cntreg===0.U),size+delay-1)){
        for(i <- 0 until size){
                for(k <- 0 until size-1){
                    rowregarray(i)(k) := columnregarray(k)(i)
                }
                rowregarray(i)(size-1) := io.in(i)
            validregarray(i) := true.B
        }
    }
}

class NTTport (implicit val conf:Config) extends Bundle{
    val in = Input(Vec(conf.nttsize,SInt(conf.wordbits.W)))
    val out = Output(Vec(conf.nttsize,SInt(conf.wordbits.W)))
    val validin = Input(Bool())
    val validout = Output(Bool())
}

// Assuming Queue(no bubble)
class INTT(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
        val in = Input(Vec(conf.nttsize,UInt(conf.Qbit.W)))
        val out = Output(Vec(conf.nttsize,SInt(conf.wordbits.W)))
        val validin = Input(Bool())
        val validout = Output(Bool())
    })

    val formerinttbut =  Module(new INTTradixButterflyUnitRadix8(conf.nttsize,conf))
    val laterinttbut = Module(new INTTradixButterflyUnit64)

    def reverse(in: Int, n: Int, out: Int = 0): Int =
        if (n == 0) out
        else reverse(in >>> 1, n - 1, (out << 1) | (in & 1))
    
    val twistbus = Wire(Vec(conf.nttsize,Vec(conf.numcycle, SInt(conf.wordbits.W))))
    for(i <- 0 until conf.nttsize){
        for(j <- 0 until conf.numcycle){
            twistbus(i)(j) := conf.intttwist(i*conf.numcycle+j).S
        }
    }
    val twidlebus = Wire(Vec(conf.nttsize,Vec(conf.numcycle,SInt(conf.wordbits.W))))
    val twidleblock = conf.nttsize/conf.radix
    for(i <- 0 until conf.radix){
        for(j <- 0 until twidleblock){
            for(k <- 0 until conf.numcycle){
                twidlebus(i*twidleblock+j)(k) := conf.intttable(if(i>1) 1 else 0)(reverse(i,conf.radixbit)*(j*conf.numcycle+k)).S
            }
        }
    }

    val cntreg = RegInit(0.U(conf.cyclebit.W))
    for(i <- 0 until twidleblock){
        val lbuf = Module(new DoubleLbuffer(conf.radix, conf.wordbits,1+conf.muldelay+conf.radixdelay+1+conf.muldelay))
        lbuf.io.validin := io.validin
        for(j <- 0 until conf.radix){
            val twistmul = Module(new INTorusMULSREDC)
            twistmul.io.A := RegNext(Mux(io.validin,io.in(j*twidleblock+i).asSInt,0.S))
            twistmul.io.B := RegNext(twistbus(j*twidleblock+i)(cntreg))
            formerinttbut.io.in(j*twidleblock+i) := twistmul.io.Y
            if(j==0){
                lbuf.io.in(j) := ShiftRegister(formerinttbut.io.out(j*twidleblock+i), conf.muldelay+1)
            }else{
                val twidlemul = Module(new INTorusMULSREDC)
                twidlemul.io.A := RegNext(formerinttbut.io.out(j*twidleblock+i))
                twidlemul.io.B := RegNext(twidlebus(j*twidleblock+i)(ShiftRegister(cntreg,1+conf.muldelay+conf.radixdelay)))
                lbuf.io.in(j) := twidlemul.io.Y
            }
            laterinttbut.io.in(i*conf.radix+j) := RegNext(lbuf.io.out(j))
            if(i==0) io.validout := ShiftRegister(lbuf.io.validout,conf.radixdelay*2+conf.muldelay+2)
        }
    }
    io.out := RegNext(laterinttbut.io.out)

    when(io.validin){
        cntreg := cntreg + 1.U
        when(cntreg === (conf.numcycle-1).U){
            cntreg := 0.U
        }
    }.otherwise{
        cntreg := 0.U
    }
}

// Assuming MULandACC as Queue(no bubble)
class NTT(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
        val in = Input(Vec(conf.nttsize,SInt(conf.wordbits.W)))
        val out = Output(Vec(conf.nttsize,UInt(conf.Qbit.W)))
        val validin = Input(Bool())
        val validout = Output(Bool())
    })

    val formernttbut =  Module(new NTTradixButterflyUnit64)
    val laternttbut = Module(new NTTradixButterflyUnitRadix8(conf.nttsize,conf))

    def reverse(in: Int, n: Int, out: Int = 0): Int =
        if (n == 0) out
        else reverse(in >>> 1, n - 1, (out << 1) | (in & 1))
    
    val twistbus = Wire(Vec(conf.nttsize,Vec(conf.numcycle, SInt(conf.wordbits.W))))
    for(i <- 0 until conf.nttsize){
        for(j <- 0 until conf.numcycle){
            twistbus(i)(j) := conf.ntttwist(i*conf.numcycle+j).S
        }
    }
    val twidlebus = Wire(Vec(conf.nttsize,Vec(conf.numcycle,SInt(conf.wordbits.W))))
    val twidleblock = conf.nttsize/conf.radix
    for(i <- 0 until conf.radix){
        for(j <- 0 until twidleblock){
            for(k <- 0 until conf.numcycle){
                val index = j*conf.numcycle + k
                twidlebus(i*twidleblock+j)(k) := conf.ntttable(if(((index>>(conf.nttsizebit-conf.radixbit))&((1<<(conf.radixbit-1))-1))!=0) 1 else 0)(reverse(i,conf.radixbit)*(index)).S
            }
        }
    }

    val lbufvalid = Wire(Bool())
    val cntreg = RegInit(0.U(conf.cyclebit.W))
    for(i <- 0 until twidleblock){
        val lbuf = Module(new DoubleLbuffer(conf.radix, conf.wordbits,conf.radixdelay*2+conf.muldelay))
        lbuf.io.validin := io.validin
        for(j <- 0 until conf.radix){
            formernttbut.io.in(i*conf.radix+j) := RegNext(Mux(io.validin,io.in(i*conf.radix+j),0.S))
            lbuf.io.in(j) := RegNext(formernttbut.io.out(i*conf.radix+j))
            if( j == 0){
                if((i &((1<<(conf.radixbit-1))-1))==0){
                    laternttbut.io.in(j*twidleblock+i) := ShiftRegister(lbuf.io.out(j),1+conf.muldelay+1)
                }else{
                    val twidlemul = Module(new INTorusMULSREDC)
                    twidlemul.io.A := RegNext(lbuf.io.out(j))
                    twidlemul.io.B := conf.R2.S
                    laternttbut.io.in(j*twidleblock+i) := RegNext(twidlemul.io.Y)
                }
            }else{
                val twidlemul = Module(new INTorusMULSREDC)
                twidlemul.io.A := RegNext(lbuf.io.out(j))
                twidlemul.io.B := RegNext(twidlebus(j*twidleblock+i)(cntreg))
                laternttbut.io.in(j*twidleblock+i) := RegNext(twidlemul.io.Y)
            }
            val twistmul = Module(new INTorusMULSREDC)
            twistmul.io.A := RegNext(laternttbut.io.out(j*twidleblock+i))
            twistmul.io.B := RegNext(twistbus(j*twidleblock+i)(ShiftRegister(cntreg,1+conf.muldelay+1+conf.radixdelay-1)))
            val posres = RegNext(Mux(twistmul.io.Y<0.S,twistmul.io.Y+conf.P.S,twistmul.io.Y)(conf.wordbits-1,0))
            io.out(j*twidleblock+i) := RegNext((RegNext(posres * ((1L<<(32+conf.wordbits-1))/conf.P).U) + (1L << (conf.wordbits-1 - 1)).U)>>(conf.wordbits-1))
            if(i==0) lbufvalid := lbuf.io.validout
        }
    }
    io.validout := ShiftRegister(lbufvalid,conf.muldelay+1+conf.radixdelay+1+conf.muldelay+1+1+1)

    when(lbufvalid){
        cntreg := cntreg + 1.U
        when(cntreg === (conf.numcycle-1).U){
            cntreg := 0.U
        }
    }.otherwise{
        cntreg := 0.U
    }
}