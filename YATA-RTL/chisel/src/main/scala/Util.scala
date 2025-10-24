import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline

// https://www.chisel-lang.org/chisel3/docs/explanations/memories.html
// Simple Single Port
class RWSmem(depth:Int, width:Int) extends Module {
  val io = IO(new Bundle {
    val wen = Input(Bool())
    val addr = Input(UInt(log2Ceil(depth).W))
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })

  val mem = SyncReadMem(depth, UInt(width.W))
  io.out := DontCare
  val rdwrPort = mem(io.addr)
  when (io.wen) { rdwrPort := io.in }
    .otherwise    { io.out := rdwrPort }
}

//Write-First Two Port(1W-1R) shared address mem
class WFTPSAmem(depth:Int, width:Int) extends Module {
  val io = IO(new Bundle {
    val wen = Input(Bool())
    val addr = Input(UInt(log2Ceil(depth).W))
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })

  val mem = SyncReadMem(depth, UInt(width.W),SyncReadMem.WriteFirst)
  io.out := mem(io.addr)
  when (io.wen) { mem(io.addr) := io.in }
}

// Simple Dual Port
class RWDmem(depth:Int, width:Int) extends Module {
  val io = IO(new Bundle {
    val wen = Input(Bool())
    val waddr = Input(UInt(log2Ceil(depth).W))
    val raddr = Input(UInt(log2Ceil(depth).W))
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })

  val mem = SyncReadMem(depth, UInt(width.W))
  io.out := mem(io.raddr)
  when (io.wen) { mem(io.waddr) := io.in }
}

// Simple Single Port + Additional Read Port
class RWSRmem(depth:Int, width:Int) extends Module {
  val io = IO(new Bundle {
    val wen = Input(Bool())
    val addr = Input(UInt(log2Ceil(depth).W))
    val raddr = Input(UInt(log2Ceil(depth).W))
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
    val rout = Output(UInt(width.W))
  })

  val mem = SyncReadMem(depth, UInt(width.W))
  io.rout := mem(io.raddr)
  io.out := DontCare
  val rdwrPort = mem(io.addr)
  when (io.wen) { rdwrPort := io.in }
    .otherwise    { io.out := rdwrPort }
}

class Buffer(width: Int, depth: Int, useQueue: Boolean, hasFlush: Boolean) extends Module{
  val io = IO(new Bundle {
    val axi4sin = new AXI4StreamSubordinate(width)
    val axi4sout = new AXI4StreamManager(width)
    val flush = if (hasFlush) Some(Input(Bool())) else None
  })
  val flush = io.flush.getOrElse(false.B)
  if(useQueue){
    val queue = Module(new Queue(UInt(width.W),depth, useSyncReadMem = true, hasFlush = hasFlush))
    queue.io.enq.valid := io.axi4sin.TVALID
    queue.io.enq.bits := io.axi4sin.TDATA
    io.axi4sin.TREADY := queue.io.enq.ready

    io.axi4sout.TVALID := queue.io.deq.valid
    io.axi4sout.TDATA := queue.io.deq.bits
    queue.io.deq.ready := io.axi4sout.TREADY

    if(hasFlush){
      queue.io.flush.get := flush
    }
  }else{
    //https://github.com/chipsalliance/chisel/blob/468cc3a7d3305a00b8914a9e466038fcfc6f3a40/src/main/scala/chisel3/util/Decoupled.scala#L244-L292
    val ram = Module(new RWSmem(depth, width))

    val enq_ptr = Counter(depth)
    val deq_ptr = Counter(depth)
    val maybe_full = RegInit(false.B)
    val ptr_match = enq_ptr.value === deq_ptr.value
    val empty = ptr_match && !maybe_full
    val full = ptr_match && maybe_full
    val writing = RegInit(false.B)
    val deq_ptr_next = Mux(deq_ptr.value === (depth.U - 1.U), 0.U, deq_ptr.value + 1.U)
    val do_deq = io.axi4sout.TREADY & io.axi4sout.TVALID
    val do_enq = io.axi4sin.TVALID & (~io.axi4sout.TREADY | empty) & !full
    val r_addr = WireDefault(Mux(do_deq, deq_ptr_next, deq_ptr.value))

    //If we have to read during writing, use saved value.
    val readreg = Reg(UInt(width.W))

    io.axi4sin.TREADY := do_enq
    io.axi4sout.TVALID := RegNext(!empty) & !empty
    io.axi4sout.TDATA  := Mux(writing,readreg,ram.io.out)

    ram.io.addr := r_addr
    ram.io.in := io.axi4sin.TDATA
    ram.io.wen := false.B

    when(writing){
      writing := false.B
    }.otherwise{
      readreg := ram.io.out
    }

    //Read has higher priority
    when(do_deq) {
      deq_ptr.inc()
    }.elsewhen(do_enq) {
      ram.io.addr := enq_ptr.value
      ram.io.wen := true.B
      writing := true.B
      enq_ptr.inc()
    }
    when(do_enq =/= do_deq) {
      maybe_full := do_enq
    }

    // when flush is high, empty the queue
    // Semantically, any enqueues happen before the flush.
    if(hasFlush){
      when(flush) {
        enq_ptr.reset()
        deq_ptr.reset()
        maybe_full := false.B
      }
    }
  }
}

class AccumulateMemory(depth:Int, width:Int, useSRAM:Boolean, implicit val conf: Config) extends Module {
  val io = IO(new Bundle {
    val wreq = Input(Bool())
    val rreq = Input(Bool())
    val in = Input(UInt(width.W))
    val out = Output(UInt(width.W))
    val flush = Input(Bool())
  })

  if(useSRAM){
    if(conf.useDualPort){
      val mem = Module(new RWDmem(depth,width))
      val raddreg = RegInit(0.U(log2Ceil(depth).W))
      val waddreg = RegInit(0.U(log2Ceil(depth).W))

      mem.io.in := io.in
      io.out := mem.io.out
      mem.io.raddr := raddreg
      mem.io.waddr := waddreg
      mem.io.wen := io.wreq
      when(io.rreq){
        raddreg := raddreg + 1.U
        when(raddreg === (depth-1).U){
          raddreg := 0.U
        }
      }
      when(io.wreq){
        waddreg := waddreg + 1.U
        when(waddreg === (depth-1).U){
          waddreg := 0.U
        }
      }
      when(io.flush){
        raddreg := 0.U
        waddreg := 0.U
      }
    }else{
      val mems = for( i <- 0 until 3) yield{
        val mem = Module(new RWSmem(depth/2,width))
        mem
      }

      val rselreg = RegInit(0.U(2.W))
      val raddreg = RegInit(0.U((log2Ceil(depth)-1).W))
      io.out := DontCare
      for( i <- 0 until 3){
        mems(i).io.in := io.in
        mems(i).io.addr := raddreg
        mems(i).io.wen := false.B
        when(RegNext(rselreg) === i.U){
          io.out := mems(i).io.out
        }
      }
      when(io.rreq){
        raddreg := raddreg + 1.U
        when(raddreg === (depth/2-1).U){
          raddreg := 0.U
          rselreg := (rselreg + 1.U) % 3.U
        }
      }

      //Write has higher priority
      val wselreg = RegInit(0.U(2.W))
      val waddreg = RegInit(0.U((log2Ceil(depth)-1).W))
      for( i <- 0 until 3){
        when(wselreg === i.U){
          mems(i).io.wen := io.wreq
          mems(i).io.addr := waddreg
        }
      }
      when(io.wreq){
        waddreg := waddreg + 1.U
        when(waddreg === (depth/2-1).U){
          waddreg := 0.U
          wselreg := (wselreg + 1.U) % 3.U
        }
      }
      when(io.flush){
        rselreg := 0.U
        raddreg := 0.U
        wselreg := 0.U
        waddreg := 0.U
      }
    }
  }else{
    io.out := ShiftRegister(io.in,conf.numcycle-conf.muldelay+1-2)
  }
}

class BK2Formerslice(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val subordinate = new AXI4StreamSubordinate(conf.buswidth)
		val manager = new AXI4StreamManager(conf.buswidth)
	})
	val slice = Module(new AXI4StreamRegisterSlice(conf.buswidth,conf.axi4snumslice))
	io.subordinate <> slice.io.subordinate
	io.manager <> slice.io.manager
}

class NTTdataPipeline(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val subordinate = new AXI4StreamSubordinate(conf.buswidth)
		val manager = new AXI4StreamManager(conf.buswidth)
	})
	io.subordinate.TREADY := true.B
	io.manager.TDATA := ShiftRegister(io.subordinate.TDATA,conf.axi4snumslice)
	io.manager.TVALID := ShiftRegister(io.subordinate.TVALID,conf.axi4snumslice)
}

class GlobalInslice(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val subordinate = new AXI4StreamSubordinate(conf.buswidth)
		val manager = new AXI4StreamManager(conf.buswidth)
	})
	val slice = Module(new AXI4StreamRegisterSlice(conf.buswidth,conf.axi4snumslice))
	io.subordinate <> slice.io.subordinate
	io.manager <> slice.io.manager
}

class GlobalOutslice(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val subordinate = new AXI4StreamSubordinate(2*conf.buswidth)
		val manager = new AXI4StreamManager(2*conf.buswidth)
	})
	val slice = Module(new AXI4StreamRegisterSlice(2*conf.buswidth,conf.axi4snumslice))
	io.subordinate <> slice.io.subordinate
	io.manager <> slice.io.manager
}