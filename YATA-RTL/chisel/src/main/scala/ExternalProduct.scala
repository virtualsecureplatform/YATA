import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

import math.log
import math.ceil

object DecompositionState extends ChiselEnum {
  val WAIT, INIT, RUN, LAST = Value
}

class Decomposition(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val in = Input(UInt((conf.nttsize*conf.Qbit).W))
		val out = Output(Vec(conf.nttsize,UInt(conf.Qbit.W)))
        val validin = Input(Bool())
        val validout = Output(Bool())
	})

    val digitreg = RegInit(0.U(log2Ceil(conf.l).W))
    val cyclereg = RegInit(0.U(log2Ceil(conf.numcycle*(conf.k+1)).W))

    val decmem = Module(new WFTPSAmem((conf.k+1)*conf.numcycle,conf.nttsize*conf.Qbit))
    decmem.io.in := io.in
    decmem.io.wen := false.B
    decmem.io.addr := cyclereg

    io.validout := false.B

    val statereg = RegInit(DecompositionState.WAIT)
    switch(statereg){
        is(DecompositionState.WAIT){
            io.validout := RegNext(io.validin)
            when(io.validin){
                decmem.io.wen := true.B
                cyclereg := cyclereg + 1.U
                statereg := DecompositionState.INIT
            }
        }
        is(DecompositionState.INIT){
            io.validout := RegNext(io.validin)
            when(io.validin){
                decmem.io.wen := true.B
                when(cyclereg=/=((conf.k+1)*conf.numcycle-1).U){
                    cyclereg := cyclereg + 1.U
                }.otherwise{
                    cyclereg := 0.U
                    digitreg := digitreg + 1.U
                    statereg := DecompositionState.RUN
                }
            }
        }
        is(DecompositionState.RUN){
            io.validout := true.B
            when(cyclereg=/=((conf.k+1)*conf.numcycle-1).U){
                cyclereg := cyclereg + 1.U
            }.otherwise{
                cyclereg := 0.U
                digitreg := digitreg + 1.U
                statereg := DecompositionState.RUN
                when(digitreg =/= (conf.l-1).U){
                    digitreg := digitreg + 1.U
                }.otherwise{
                    digitreg := 0.U
                    statereg := DecompositionState.LAST
                }
            }
        }
        is(DecompositionState.LAST){
            io.validout := true.B
            statereg := DecompositionState.WAIT
        }
    }

	def offsetgen(implicit conf: Config): Long = {
		var offset :Long = 0
		for(i <- 1 to conf.l){
			offset = offset + conf.Bg/2 * (1L<<(conf.Qbit - i * conf.Bgbit))
		}
		offset
	}

	val offset: Long = offsetgen(conf)
	val raundoffset: Long = 1L << (conf.Qbit - conf.l * conf.Bgbit - 1)

    for(j <- 0 until conf.nttsize){
    val addedoffset = decmem.io.out((j+1)*conf.Qbit-1,j*conf.Qbit) + (offset + raundoffset).U
    val extnum = Wire(Vec(conf.l,UInt(conf.Qbit.W)))
        for(k <- 0 until conf.l){
            extnum(k) := addedoffset(conf.Qbit-k*conf.Bgbit-1,conf.Qbit-(k+1)*conf.Bgbit)
        }
        io.out(j) := extnum(RegNext(digitreg)) - (conf.Bg/2).U
    }
}

object MULandACCState extends ChiselEnum {
  val RUN, DELAY, OUT = Value
}

class MULandACCpolynomial(delay: Int,implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
		val in = Input(UInt((conf.nttsize*conf.wordbits).W))
		val out = Output(UInt((conf.nttsize*conf.wordbits).W))
        val trgswin = Input(Vec(conf.nttsize,SInt(conf.wordbits.W)))
        val trgswinvalid = Input(Bool())
        val trgswinready = Output(Bool())
        val validin = Input(Bool())
        val validout = Output(Bool())
        
        val debugvalid = Output(Bool())
        val debugout = Output(UInt((conf.nttsize*32).W))
	})
    io.trgswinready := false.B

    val accmem = Module(new AccumulateMemory(conf.numcycle,conf.nttsize*conf.wordbits, (delay!=0)|(conf.useSRAM), conf))

    val cyclereg = RegInit(0.U(log2Ceil(conf.numcycle*(if(delay>=1)delay else 1)).W))

    io.out := accmem.io.out
    val digitreg = RegInit(0.U(log2Ceil((conf.k+1)*conf.l).W))
    val wenwire = Wire(Bool())
    val validwire = Wire(Bool())
    wenwire := false.B
    validwire := false.B
    io.validout := RegNext(validwire)

    val accbus = Wire(Vec(conf.nttsize,SInt(conf.wordbits.W)))
    val debugbus = Wire(Vec(conf.nttsize,SInt(32.W)))
    for(k <- 0 until conf.nttsize){
        val mul = Module(new INTorusMULSREDC)
        mul.io.A := io.in((k+1)*conf.wordbits-1,k*conf.wordbits).asSInt
        mul.io.B := io.trgswin(k)
        val add = Module(new INTorusADD)
        add.io.A := RegNext(mul.io.Y)
        add.io.B := RegNext(Mux(ShiftRegister(digitreg===0.U,conf.muldelay),0.S,ShiftRegister(accmem.io.out((k+1)*conf.wordbits-1,k*conf.wordbits).asSInt,conf.muldelay-1)))
        accbus(k) := add.io.Y
        debugbus(k) := add.io.Y
    }
    accmem.io.rreq := false.B
    accmem.io.wreq := ShiftRegister(wenwire,conf.muldelay+1+1)
    accmem.io.in := Cat(accbus.reverse)
    accmem.io.flush := false.B
    io.debugout := Cat(debugbus.reverse)
    io.debugvalid := accmem.io.wreq

    val statereg = RegInit(MULandACCState.RUN)

    switch(statereg){
        is(MULandACCState.RUN){
            when(io.validin && io.trgswinvalid){
                io.trgswinready := true.B
                wenwire := true.B
                when(digitreg=/=0.U){
                    accmem.io.rreq := true.B
                }
                when(cyclereg=/=(conf.numcycle-1).U){
                    cyclereg := cyclereg + 1.U
                }.otherwise{
                    cyclereg := 0.U
                    when(digitreg =/= ((conf.k+1)*conf.l-1).U){
                        digitreg := digitreg + 1.U
                    }.otherwise{
                        digitreg := 0.U
                        if(delay==0){
                            statereg := MULandACCState.OUT
                        }else{
                            statereg := MULandACCState.DELAY
                        }
                    }
                }
            }
        }
        is(MULandACCState.DELAY){
            cyclereg := cyclereg + 1.U
            when(cyclereg===((if(delay>=1)delay else 1)*conf.numcycle-1).U){
                cyclereg := 0.U
                statereg := MULandACCState.OUT
            }
        }
        is(MULandACCState.OUT){
            validwire := true.B
            accmem.io.rreq := true.B
            cyclereg := cyclereg + 1.U
            when(cyclereg===(conf.numcycle-1).U){
                cyclereg := 0.U
                statereg := MULandACCState.RUN
                accmem.io.flush := true.B
            }
        }
    }
}

class MULandACC(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
		val in = Input(UInt((conf.nttsize*conf.wordbits).W))
		val out = Output(UInt((conf.nttsize*conf.wordbits).W))
        val trgswin = Input(Vec(conf.k+1,Vec(conf.nttsize,SInt(conf.wordbits.W))))
        val trgswinvalid = Input(Vec(conf.k+1,Bool()))
        val trgswinready = Output(Vec(conf.k+1,Bool()))
        val validin = Input(Bool())
        val validout = Output(Bool())
        
        val debugvalid = Output(Vec(conf.k+1,Bool()))
        val debugout = Output(Vec(conf.k+1,UInt((conf.nttsize*32).W)))
	})

    val mulaccpolys = for(i <- 0 until conf.k+1) yield{
        val mulaccpoly = Module(new MULandACCpolynomial(i,conf))
        mulaccpoly
    }
    for(i <- 0 until conf.k){
        mulaccpolys(i).io.in := ShiftRegister(mulaccpolys(i+1).io.in,conf.accnumslice)
        mulaccpolys(i).io.validin := ShiftRegister(mulaccpolys(i+1).io.validin,conf.accnumslice)
    }
    mulaccpolys(conf.k).io.in := io.in
    mulaccpolys(conf.k).io.validin := io.validin
    for(i <- 0 until conf.k+1){
        mulaccpolys(i).io.trgswin := io.trgswin(i)
        mulaccpolys(i).io.trgswinvalid := io.trgswinvalid(i)
        io.trgswinready(i) := mulaccpolys(i).io.trgswinready
        io.debugvalid(i) := mulaccpolys(i).io.debugvalid
        io.debugout(i) := mulaccpolys(i).io.debugout
    }
    val outwire = Wire(Vec(conf.k+1,UInt((conf.nttsize*conf.wordbits).W)))
    val validwire = Wire(Vec(conf.k+1,Bool()))
    for(k <- 0 until conf.k){
        outwire(k) := Mux(mulaccpolys(k).io.validout,mulaccpolys(k).io.out,ShiftRegister(outwire(k+1),conf.accnumslice))
        validwire(k) := mulaccpolys(k).io.validout | ShiftRegister(validwire(k+1),conf.accnumslice)
    }
    outwire(conf.k) := mulaccpolys(conf.k).io.out
    validwire(conf.k) := mulaccpolys(conf.k).io.validout
    io.out := outwire(0)
    io.validout := validwire(0)
}

class ExternalProductFormer(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
        val axi4sin = Vec(conf.trlwenumbus,new AXI4StreamSubordinate(conf.buswidth))
        val axi4sout = Vec(conf.trlwenumbus,new AXI4StreamManager(conf.buswidth))
	})
    val tdatavec = Wire(Vec(conf.trlwenumbus,UInt(conf.buswidth.W)))
    for(i <- 0 until conf.trlwenumbus){
        io.axi4sin(i).TREADY := true.B
        tdatavec(i) := io.axi4sin(i).TDATA
    }

	val decomp = Module(new Decomposition)
    decomp.io.in := Cat(tdatavec.reverse)
    decomp.io.validin := io.axi4sin(0).TVALID


    for(i <- 0 until conf.trlwenumbus){
        io.axi4sout(i).TVALID := decomp.io.validout
        io.axi4sout(i).TDATA :=Cat(decomp.io.out.reverse)((i+1)*conf.buswidth-1,i*conf.buswidth)
    }
}

class ExternalProductPreMiddle(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
        val axi4sin = Vec(conf.trlwenumbus,new AXI4StreamSubordinate(conf.buswidth))
        val axi4sout = Vec(conf.nttnumbus,new AXI4StreamManager(conf.buswidth))

        val inttvalidout = Output(Bool())
        val inttout = Output(Vec(conf.nttsize,SInt(32.W)))
	})

    val intt = Module(new INTT)
    
    io.inttvalidout := intt.io.validout

    val tdatavec = Wire(Vec(conf.trlwenumbus,UInt(conf.buswidth.W)))
    for(i <- 0 until conf.trlwenumbus){
        io.axi4sin(i).TREADY := true.B
        tdatavec(i) := io.axi4sin(i).TDATA
    }
    for(i <- 0 until conf.nttsize){
        intt.io.in(i) :=  Cat(tdatavec.reverse)((i+1)*conf.Qbit-1,i*conf.Qbit)
        io.inttout(i) := intt.io.out(i)
    }
    intt.io.validin := io.axi4sin(0).TVALID
    val outwire = Wire(UInt((conf.nttnumbus*conf.buswidth).W))
    outwire := Cat(intt.io.out.reverse)

    for(i <- 0 until conf.nttnumbus){
        io.axi4sout(i).TVALID := ShiftRegister(intt.io.validout,conf.interslr)
        io.axi4sout(i).TDATA := ShiftRegister(outwire((i+1)*conf.buswidth-1,i*conf.buswidth),conf.interslr)
    }
}

class ExternalProductMiddle(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
        val axi4sin = Vec(conf.nttnumbus,new AXI4StreamSubordinate(conf.buswidth))
        val axi4sout = Vec(conf.nttnumbus,new AXI4StreamManager(conf.buswidth))
		val trgswin = Input(Vec(conf.k+1,UInt((conf.nttsize*32).W)))
        val trgswinvalid = Input(Vec(conf.k+1,Bool()))
        val trgswinready = Output(Vec(conf.k+1,Bool()))

        val accvalid = Output(Vec(conf.k+1,Bool()))
        val accout = Output(UInt(((conf.k+1)*conf.nttsize*conf.wordbits).W))
	})

    val mulandacc = Module(new MULandACC)
    mulandacc.io.trgswinvalid := io.trgswinvalid
    io.trgswinready := mulandacc.io.trgswinready
    for(i<-0 until conf.k+1){
        for(k <- 0 until conf.nttsize){
            mulandacc.io.trgswin(i)(k) := io.trgswin(i)((k+1)*32-1,k*32).asSInt
        }
    }

    val tdatavec = Wire(Vec(conf.nttnumbus,UInt(conf.buswidth.W)))
	for(i <- 0 until conf.nttnumbus){
		io.axi4sin(i).TREADY := true.B
		tdatavec(i) :=  ShiftRegister(io.axi4sin(i).TDATA,conf.interslr)
	}
    mulandacc.io.in := Cat(tdatavec.reverse)
    mulandacc.io.validin := ShiftRegister(io.axi4sin(0).TVALID,conf.interslr)

    io.accout := Cat(mulandacc.io.debugout.reverse)
    io.accvalid := mulandacc.io.debugvalid
    
    val outwire = Wire(UInt((conf.nttnumbus*conf.buswidth).W))
    outwire :=  mulandacc.io.out
    for(i <- 0 until conf.nttnumbus){
        io.axi4sout(i).TVALID := mulandacc.io.validout
        io.axi4sout(i).TDATA := outwire((i+1)*conf.buswidth-1,i*conf.buswidth)
    }
}

class ExternalProductLater(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
        val axi4sin = Vec(conf.nttnumbus,new AXI4StreamSubordinate(conf.buswidth))
        val axi4sout = Vec(conf.trlwenumbus,new AXI4StreamManager(conf.buswidth))
	})

    val ntt = Module(new NTT)
    ntt.io.validin := RegNext(io.axi4sin(0).TVALID)

    val tdatavec = Wire(Vec(conf.nttnumbus,UInt(conf.buswidth.W)))
    for(i <- 0 until conf.nttnumbus){
        io.axi4sin(i).TREADY:= true.B
        tdatavec(i) := io.axi4sin(i).TDATA
    }
    for(i <- 0 until conf.nttsize){
        ntt.io.in(i) := RegNext(Cat(tdatavec.reverse)((i+1)*conf.wordbits-1,i*conf.wordbits)).asSInt
    }
    for(i <- 0 until conf.trlwenumbus){
        io.axi4sout(i).TVALID :=  ShiftRegister(ntt.io.validout,conf.interslr/2)
        io.axi4sout(i).TDATA := ShiftRegister(Cat(ntt.io.out.reverse)((i+1)*conf.buswidth-1,i*conf.buswidth),conf.interslr/2)
    }
}

class ExternalProduct(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val in = Input(Vec(conf.nttsize,UInt(conf.Qbit.W)))
        val out = Output(Vec(conf.nttsize,UInt(conf.Qbit.W)))
		val trgswin = Input(Vec(conf.k+1,UInt((conf.nttsize*32).W)))
        val trgswinvalid = Input(Vec((conf.k+1),Bool()))
        val trgswinready = Output(Vec((conf.k+1),Bool()))

        val validin = Input(Bool())
        val validout = Output(Bool())
        val fin = Output(Bool())

        val inttvalidout = Output(Bool())
        val inttout = Output(Vec(conf.nttsize,SInt(32.W)))

        val accvalid = Output(Vec(conf.k+1,Bool()))
        val accout = Output(UInt(((conf.k+1)*conf.nttsize*32).W))
	})

    val former = Module(new ExternalProductFormer)
    for(i <- 0 until conf.trlwenumbus){
        former.io.axi4sin(i).TVALID := io.validin
        former.io.axi4sin(i).TDATA := Cat(io.in.reverse)((i+1)*conf.buswidth-1,i*conf.buswidth)
    }
    
    val premiddle = Module(new ExternalProductPreMiddle)
    premiddle.io.axi4sin <> former.io.axi4sout
    io.inttvalidout := premiddle.io.inttvalidout
    io.inttout := premiddle.io.inttout

    val middle = Module(new ExternalProductMiddle)
    middle.io.axi4sin <> premiddle.io.axi4sout
    middle.io.trgswin := io.trgswin
    middle.io.trgswinvalid := io.trgswinvalid
    io.trgswinready := middle.io.trgswinready
    io.accvalid := middle.io.accvalid
    io.accout := middle.io.accout

    val later = Module(new ExternalProductLater)
    middle.io.axi4sout <> later.io.axi4sin

    val tdatavec = Wire(Vec(conf.trlwenumbus,UInt(conf.buswidth.W)))
	for(i <- 0 until conf.trlwenumbus){
		later.io.axi4sout(i).TREADY := true.B
		tdatavec(i) :=  ShiftRegister(later.io.axi4sout(i).TDATA,conf.interslr/2)
	}
    for(i <- 0 until conf.nttsize){
        io.out(i) := Cat(tdatavec.reverse)((i+1)*conf.Qbit-1,i*conf.Qbit)
    }

    io.validout := ShiftRegister(later.io.axi4sout(0).TVALID,conf.interslr/2)
    io.fin := ~io.validout && RegNext(io.validout)
}