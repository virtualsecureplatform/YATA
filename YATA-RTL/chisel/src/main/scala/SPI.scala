import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.util.AsyncQueue
import freechips.rocketchip.util.AsyncQueueParams

class SPIport(buswidth : Int = Config().spibuswidth)(implicit val conf:Config) extends Bundle{
    val SS = Input(Bool())
    val MOSI = Input(UInt(buswidth.W))
    val MISO = Output(UInt(buswidth.W))
    val SCLK = Input(Clock())
}

class SPIportPAD(buswidth : Int = Config().spibuswidth)(implicit val conf:Config) extends Bundle{
    val SS = Analog(1.W)
    val MOSI = Analog(buswidth.W)
    val MISO = Analog(buswidth.W)
    val SCLK = Analog(1.W)
}

object SPIConfigState extends ChiselEnum {
  val Input, Write, Repeat  = Value
}

class SPIConfigModule()(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
		val SPI = new SPIport(1)

        val Overflow = Input(Bool())
        val Underflow = Input(Bool())
        
        val compress = Output(UInt((conf.k+1).W))
        val maxpower = Output(Bool())

        // from shift registers
        val PLLRESET = Output(Bool()) //active low
        val PLLDLF_Clear = Output(Bool())
        val PLLDLF_En = Output(Bool())
        val PLLDLF_Ext_Override = Output(Bool())
        val PLLDLF_IN_POL = Output(Bool())
        val PLLRC = Output(UInt(3.W))
        val PLLDLF_Ext_Data = Output(UInt(10.W))
        val PLLDLF_KI = Output(UInt(4.W))
        val PLLDLF_KP = Output(UInt(4.W))
        val PLLMMDCLKDIV_RATIO = Output(UInt(8.W))
        val PLLCOARSEDELAYBINARY_CODE = Output(UInt(7.W))
        val PLLOUT_CLKDIV_RATIO = Output(UInt(8.W))
	})


    val MOSIasyncqueue = Module(new AsyncQueue(UInt(1.W)))
    MOSIasyncqueue.io.enq_clock := io.SPI.SCLK
    MOSIasyncqueue.io.enq_reset := reset
    MOSIasyncqueue.io.enq.valid := ~io.SPI.SS
    MOSIasyncqueue.io.enq.bits := io.SPI.MOSI
    MOSIasyncqueue.io.deq_clock := clock
    MOSIasyncqueue.io.deq_reset := reset
    MOSIasyncqueue.io.deq.ready := true.B

    val shiftReg = RegInit(VecInit(Seq.fill(conf.numConfigRegister)(0.U(1.W))))
    val MISOasyncqueue = Module(new AsyncQueue(UInt(1.W)))
    MISOasyncqueue.io.deq_clock := io.SPI.SCLK
    MISOasyncqueue.io.deq_reset := reset
    MISOasyncqueue.io.enq.valid := false.B
    MISOasyncqueue.io.enq.bits := shiftReg(0)
    MISOasyncqueue.io.enq_clock := clock
    MISOasyncqueue.io.enq_reset := reset
    MISOasyncqueue.io.deq.ready := io.SPI.SS
    io.SPI.MISO := MISOasyncqueue.io.deq.bits

    val cnt = RegInit(0.U(conf.numConfigRegisterbit.W))
    val outReg = RegInit(0.U(conf.numConfigRegister.W))

    io.maxpower := outReg(0)
    io.compress := outReg(3,1)
    io.PLLRESET := outReg(4)
    io.PLLDLF_Clear := outReg(5)
    io.PLLDLF_En := outReg(6)
    io.PLLDLF_Ext_Override := outReg(7)
    io.PLLDLF_IN_POL := outReg(8)
    io.PLLRC := outReg(11,9)
    io.PLLDLF_Ext_Data := outReg(21,12)
    io.PLLDLF_KI := outReg(25,22)
    io.PLLDLF_KP := outReg(29,26)
    io.PLLMMDCLKDIV_RATIO := outReg(37,30)
    io.PLLCOARSEDELAYBINARY_CODE := outReg(44,38)
    io.PLLOUT_CLKDIV_RATIO := outReg(52,45)

    val statereg = RegInit(SPIConfigState.Input)
    switch(statereg){
        is(SPIConfigState.Input){
            when(MOSIasyncqueue.io.deq.valid){
                cnt := cnt + 1.U
                shiftReg(conf.numConfigRegister-1) := MOSIasyncqueue.io.deq.bits
                for(i<-0 until conf.numConfigRegister-1){
                    shiftReg(i) := shiftReg(i+1) 
                }
                when(cnt === (conf.numConfigRegister - 1).U){
                    cnt := 0.U
                    statereg := SPIConfigState.Write
                }
            }
        }
        is(SPIConfigState.Write){
            outReg := Cat(shiftReg.reverse)
            statereg := SPIConfigState.Repeat
            shiftReg(62) := io.Overflow
            shiftReg(63) := io.Underflow
        }
        is(SPIConfigState.Repeat){
            MISOasyncqueue.io.enq.valid := true.B
            when(MISOasyncqueue.io.enq.ready){
                cnt := cnt + 1.U
                for(i<-0 until conf.numConfigRegister-1){
                    shiftReg(i) := shiftReg(i+1) 
                }
                when(cnt === (conf.numConfigRegister - 1).U){
                    cnt := 0.U
                    statereg := SPIConfigState.Input
                }
            }
        }
    }
}

object SPIControlState extends ChiselEnum {
  val Command, Count  = Value
}

class SPIControlModule()(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
		val SPI = new SPIport()
        val axi4sout = new AXI4StreamSubordinate(conf.spibuswidth)

        val axi4sinfifo = new AXI4StreamManager(conf.spibuswidth)

        val axi4sbkfifo = Vec(conf.k+1,new AXI4StreamManager(conf.spibuswidth))

        val axi4sprngseed= new AXI4StreamManager(conf.spibuswidth)

        //States
        val fin = Input(Bool())
        val run = Output(Bool())
        val flush = Output(Bool())
        val bkflush = Output(Bool())
        val incn = Input(Bool())
        val continue = Output(Bool())

        val maxpower = Input(Bool())
	})
    io.run := false.B
    io.flush := false.B
    io.bkflush := io.flush


    val MISOasyncqueue = Module(new AsyncQueue(UInt(conf.spibuswidth.W)))
    MISOasyncqueue.io.deq_clock := io.SPI.SCLK
    MISOasyncqueue.io.deq_reset := reset
    MISOasyncqueue.io.enq.valid := io.axi4sout.TVALID
    MISOasyncqueue.io.enq.bits := io.axi4sout.TDATA
    io.axi4sout.TREADY := MISOasyncqueue.io.enq.ready
    MISOasyncqueue.io.enq_clock := clock
    MISOasyncqueue.io.enq_reset := io.flush
    MISOasyncqueue.io.deq.ready := false.B

    val MOSIasyncqueue = Module(new AsyncQueue(UInt(conf.spibuswidth.W)))
    MOSIasyncqueue.io.enq_clock := io.SPI.SCLK
    MOSIasyncqueue.io.enq_reset := io.SPI.SS
    MOSIasyncqueue.io.enq.valid := true.B
    MOSIasyncqueue.io.enq.bits := io.SPI.MOSI
    MOSIasyncqueue.io.deq_clock := clock
    MOSIasyncqueue.io.deq_reset := reset
    MOSIasyncqueue.io.deq.ready := true.B

    val runreg = RegInit(false.B)
    io.run := runreg
    val finreg = RegNext(io.fin)
    io.SPI.MISO := false.B
    when(finreg){
        runreg := false.B | RegNext(io.maxpower)
    }

    io.axi4sinfifo.TVALID := false.B
    io.axi4sinfifo.TDATA := MOSIasyncqueue.io.deq.bits
    io.axi4sprngseed.TDATA := MOSIasyncqueue.io.deq.bits
    io.axi4sprngseed.TVALID := false.B
    for(i <- 0 until conf.k+1){
        io.axi4sbkfifo(i).TDATA := MOSIasyncqueue.io.deq.bits
        io.axi4sbkfifo(i).TVALID := false.B
    }
    
    val numcommandreg = 8/conf.spibuswidth
    val commandreg = Reg(Vec(numcommandreg,UInt(conf.spibuswidth.W)))
    val command = Cat(commandreg.reverse)
    val statereg = RegInit(SPIControlState.Command)
    val cntreg = RegInit(0.U(3.W))
    val brreg = RegInit(0.U(log2Ceil(conf.n).W))
    when(io.incn & (brreg=/=0.U)){
        brreg := brreg - 1.U
    }

    io.continue := brreg =/= 0.U
    switch(statereg){
        is(SPIControlState.Command){
            io.SPI.MISO := MOSIasyncqueue.io.enq.ready
            when(MOSIasyncqueue.io.deq.valid){
                commandreg(numcommandreg-1) := MOSIasyncqueue.io.deq.bits
                for(i <- 0 until numcommandreg-1){
                    commandreg(i) := commandreg(i+1)
                }
                cntreg := cntreg + 1.U
                when(cntreg === (numcommandreg-1).U){
                    cntreg := 0.U
                    statereg := SPIControlState.Count
                }
            }
        }
        is(SPIControlState.Count){
            when(command(7)===1.U){
                when(command(6) === 1.U){
                    //State Controll
                    when(command(5)===1.U){
                        runreg := true.B
                        when(command(4)===1.U){
                            brreg := conf.n.U
                        }.otherwise{
                            when(MOSIasyncqueue.io.deq.valid){
                                brreg := brreg + Mux(~io.incn,MOSIasyncqueue.io.deq.bits,0.U)
                            }
                        }
                    }.otherwise{
                        io.flush := true.B
                    }
                }.otherwise{
                    //Data Communication
                    when(command(5) === 1.U){
                        //Input
                        when(command(4)===1.U){
                            io.axi4sinfifo.TVALID := MOSIasyncqueue.io.deq.valid
                            MOSIasyncqueue.io.deq.ready := io.axi4sinfifo.TREADY
                        }.otherwise{
                            val sel = Cat(command(1),command(0))
                            when(command(3)===1.U){
                                io.axi4sbkfifo(sel).TVALID := MOSIasyncqueue.io.deq.valid
                                MOSIasyncqueue.io.deq.ready := io.axi4sbkfifo(sel).TREADY
                            }.otherwise{
                                io.axi4sprngseed.TVALID := MOSIasyncqueue.io.deq.valid
                                MOSIasyncqueue.io.deq.ready := io.axi4sprngseed.TREADY
                            }
                        }
                    }.otherwise{
                        // Output
                        MISOasyncqueue.io.deq.ready := ~io.SPI.SS
                        io.SPI.MISO := MISOasyncqueue.io.deq.bits 
                    }
                } 
            }.otherwise{
                statereg := SPIControlState.Command
            }
        }
    }
    when(io.SPI.SS){
        statereg := SPIControlState.Command
        cntreg := 0.U
        io.bkflush := true.B
    }  
}