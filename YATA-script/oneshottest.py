#!/bin/bash

def reverse_byte(a):
    return ((a & 0x1) << 7) | ((a & 0x2) << 5) | \
           ((a & 0x4) << 3) | ((a & 0x8) << 1) | \
           ((a & 0x10) >> 1) | ((a & 0x20) >> 3) | \
           ((a & 0x40) >> 5) | ((a & 0x80) >> 7)

def repeat_reconstruct(ret):
    ret_rev = [reverse_byte(x) for x in ret]
    val = ret_rev[0]>>4
    for i in range(8):
        val = val + (ret_rev[i+1]<<(4+8*i))
    val_str = str(hex(val))[2:].zfill(16)
    return [reverse_byte(int(val_str[x:x+2],16)) for x in range(0, len(val_str), 2)][::-1]

maxpower = 0
compress = 0b000

clkfreq = int(2e7)
spifreq = clkfreq//4

def config_bytes(PLLCOARSEDELAYBINARY_CODE, PLLMMDCLKDIV_RATIO, PIgain, reset =True):
    PLLRESET = 0 # Active_low
    PLLDLF_Clear = 1 #1'b1 clear PID data, 1'b0 do nothing. this signal is inferior to reset.
    PLLDLF_En = 1 # 1'b1 Enable   1'b1 Disable of PID

    # 1'b1: using external PID value from port PLLDLF_Ext_Data  1'b0: using internal signal
    # Note that if you set this signal 1, the PLLDLF_En should be set to 0８
    PLLDLF_Ext_Override = 0
    PLLDLF_IN_POL = 1 # the polarity of control signal. + -

    PLLRC = 0 #DLF RC Value
    PLLDLF_Ext_Data = 0 # set internal DLF State

    PLLDLF_KI = PIgain["I"]# PLL I gain, hand tuned
    PLLDLF_KP = PIgain["P"]# PLL P gain hand tuned 

    PLLOUT_CLKDIV_RATIO = 0

    if(not reset):
        PLLRESET = 1
        PLLDLF_Clear = 0
        PLLRC = 0b111

    config_str=(f"{PLLOUT_CLKDIV_RATIO:08b}"+f"{PLLCOARSEDELAYBINARY_CODE:07b}"+f"{PLLMMDCLKDIV_RATIO:08b}"+f"{PLLDLF_KP:04b}"+f"{PLLDLF_KI:04b}"+f"{PLLDLF_Ext_Data:010b}"+f"{PLLRC:03b}"+f"{PLLDLF_IN_POL:01b}"+f"{PLLDLF_Ext_Override:01b}"+f"{PLLDLF_En:01b}"+f"{PLLDLF_Clear:01b}"+f"{PLLRESET:01b}"+f"{compress:03b}"+f"{maxpower:01b}")
    config_str = config_str.zfill(64)
    # config_str = 11*"1"+ config_str
    # return [reverse_byte(int(config_str[x:x+8],2)) for x in range(0, len(config_str), 8)]+[0x0]
    config_array=[reverse_byte(int(config_str[x:x+8],2)) for x in range(0, len(config_str), 8)]
    config_array.reverse()
    return config_array

import pickle

bkraintt_bytes = []

nstep = 2

lvl1k = 2
lvl1l = 2
lvl1N = 512
lvl0n = 636

wordbits = 27
nttwordsinbus = 512//32
bknumbus = 12
numcycle = 8

cmd_seed = 0b10100000;
cmd_bkfifo0 = 0b10101100
cmd_bkfifo1 = 0b10101101
cmd_bkfifo2 = 0b10101110
cmd_bkfifo = [cmd_bkfifo0,cmd_bkfifo1,cmd_bkfifo2]
cmd_infifo = 0b10110000;
cmd_run = 0b11110000;
cmd_runstep = 0b11100000
cmd_out = 0b10000000;
cmd_flush = 0b11000000;

import gpiozero
import time
import spidev
import pyvisa
import random
import sys

rm = pyvisa.ResourceManager()
powersupply = rm.open_resource('TCPIP::10.0.20.72::INSTR')
vddio = rm.open_resource('USB0::1003::8293::HEWLETT-PACKARD_E3631A_0_3.0-6.0-2.0::0::INSTR')

functiongenerator = rm.open_resource('USB0::1535::2593::LCRY2362C01679::0::INSTR')
functiongenerator.write('C1:BSWV WVTP,SQUARE,AMP,0.9,OFST,0.9,FRQ,'+str(clkfreq))

diffcounter = rm.open_resource('TCPIP::10.0.20.78::INSTR')
diffcounter.write("*RST; *CLS")
diffcounter.write("INPut1:COUPling DC")
diffcounter.write("INPut2:COUPling DC")
diffcounter.write("CONF:TINT (@1), (@2)")
diffcounter.write("INP1:RANG 50")
diffcounter.write("INP2:RANG 50")
diffcounter.write("INPut1:PROBe 10")
diffcounter.write("INPut2:PROBe 10")
diffcounter.write("INP:LEV .9")
diffcounter.write("INPut2:LEVel 0.9")
diffcounter.write("SAMP:COUN 1")

freqcounter = rm.open_resource('USB0::1003::8293::HEWLETT-PACKARD_53132A_0_4613::0::INSTR')
freqcounter.timeout = 5000
freqcounter.write("*RST; *CLS; *SRE 0; *ESE 0; :STAT:PRES")
freqcounter.write("EVENt1:LEVel:AUTO OFF")
freqcounter.write("EVENt1:LEVel 0.09")
freqcounter.write(":INPut1:COUPling DC")
freqcounter.write(":FUNC 'FREQ 1'")
freqcounter.write(":FREQ:ARM:STAR:SOUR IMM")
freqcounter.write(":FREQ:ARM:STOP:SOUR TIM")
freqcounter.write(":FREQ:ARM:STOP:TIM .100")

currentdmm = rm.open_resource('TCPIP::10.0.20.74::INSTR') # DMM7510
currentdmm.timeout = 5000
# currcount = 1000
# currentdmm.write("COUN "+str(currcount))
# currentdmm = rm.open_resource('TCPIP::10.0.20.75::INSTR') # 34456A
# currentdmm.timeout = 5000

# voltagedmm = rm.open_resource('TCPIP::10.0.20.77::INSTR') # 34411A voltage at the VDD connector.
# voltagedmm = rm.open_resource('TCPIP::10.0.20.76::INSTR') # 34410A the inner part of the chip
voltagedmm = rm.open_resource('TCPIP::10.0.20.75::INSTR') # 34465A
voltagedmm.timeout = 5000

# oscilloscope = rm.open_resource('TCPIP::10.0.20.80::INSTR') #T3DSO

spiconf = spidev.SpiDev()
spiconf.open(1,2)
spiconf.max_speed_hz = spifreq
spiconf.mode = 0b00

spiconfdummy = spidev.SpiDev()
spiconfdummy.open(1,1)
spiconfdummy.max_speed_hz = spifreq
spiconfdummy.mode = 0b00

spictrl = spidev.SpiDev()
spictrl.open(0,0)
spictrl.max_speed_hz = spifreq
spictrl.mode = 0b00

resetpin = gpiozero.DigitalOutputDevice(24)
pllbypasspin = gpiozero.DigitalOutputDevice(23)
ctrlcs = gpiozero.DigitalOutputDevice(8)
finpin = gpiozero.DigitalInputDevice(22, pull_up = None, active_state=True)
trigpin = gpiozero.DigitalOutputDevice(27)
trigpin.on()

def turnon(d=0, isPLL0V9=False):
    assert(d<=0.05)
    powersupply.write("*RST; *CLS")
    powersupply.write('APPL CH1, '+str(0.9+d)+',3.6')
    # powersupply.write('APPL CH1, 0.92,3.6')
    if isPLL0V9:
        powersupply.write('APPL CH2, 0.9,0.05')
    else:
        powersupply.write('APPL CH2, 0.8,0.05')
    powersupply.write('VOLT:SENS EXT,(@1)')
    powersupply.write('VOLT:SENS EXT,(@2)')
    powersupply.write('VOLT:PROT 3,(@1)')
    powersupply.write('VOLT:PROT 1.6,(@2)')
    powersupply.write('OUTP ON,(@2)')
    powersupply.write('OUTP ON,(@1)')
    vddio.write("*RST; *CLS")
    vddio.write("APPLy P6V, 1.8, 0.1")
    vddio.write("OUTPut ON")
    functiongenerator.write('C1:OUTP ON')

def turnoff():
    powersupply.write('OUTP OFF,(@1)')
    powersupply.write('OUTP OFF,(@2)')
    vddio.write("OUTPut OFF")
    functiongenerator.write('C1:OUTP OFF')

def spireset():
    ctrlcs.on()

    spiconf.bits_per_word = 10
    spictrl.bits_per_word = 10

    resetpin.on()
    ret = spiconfdummy.xfer([0x0,0x0])
    ret = spictrl.xfer([0x0,0x0])
    resetpin.off()

    ret = spiconfdummy.xfer([0x0,0x0])
    ret = spictrl.xfer([0x0,0x0])

def configuretest():
    spireset()
    to_send = [random.randint(0,255) for x in range(7)]+[random.randint(0,63)*4]
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(to_send.copy())
    print(ret)

    spiconfdummy.bits_per_word = 8
    ret = spiconfdummy.xfer(9*[0x00])
    print([repeat_reconstruct(ret),to_send])
    assert(repeat_reconstruct(ret)==to_send)
    print("CONFIGURE TEST PASS")

sampleratio = 20
currentsample = int(1e5)
voltagesample = int(currentsample/sampleratio)

def PowerMeasureStart():
    trigpin.on()
    voltagedmm.write("*RST; *CLS")
    voltagedmm.query('*OPC?')
    # voltagedmm.write(":SENS:VOLT:DC:APER 1e-5")
    voltagedmm.write("CONF:VOLT:DC 2")
    voltagedmm.write("VOLT:DC:APER MIN")
    # print(voltagedmm.query("VOLT:DC:APER?"))
    # voltagedmm.write("TRIG:SOUR IMM")
    voltagedmm.write("SAMP:COUN "+str(voltagesample))
    # print(voltagedmm.query("SAMP:COUN?"))
    voltagedmm.write("TRIG:SOUR EXT")
    voltagedmm.query('*OPC?')

    # DMM7510
    currentdmm.write("*RST; *CLS")
    currentdmm.write("TRAC:CLE")
    # currentdmm.write('TRAC:MAKE "buffCURR", '+str(currentsample))
    # print(currentdmm.query('TRAC:POIN? "buffCURR"'))
    currentdmm.write('TRAC:POIN '+str(currentsample)+', "defbuffer1"')
    currentdmm.write("TRAC:FILL:MODE ONCE")
    currentdmm.write('DIG:FUNC "CURR"')
    currentdmm.write('DIG:CURR:SRATE 1000000')
    currentdmm.write("DIG:CURR:APER AUTO")
    currentdmm.write("DIG:CURR:RANG 5")
    currentdmm.write("DIG:COUN "+str(currentsample))
    currentdmm.query('*OPC?')
    # 34465A
    # currentdmm.write("*RST; *CLS")
    # currentdmm.query('*OPC?')
    # currentdmm.write("CONF:CURR:DC 10")
    # currentdmm.write("CURR:DC:APER MIN")
    # # print(currentdmm.query("CURR:DC:APER?"))
    # currentdmm.write("SAMP:COUN "+str(currentsample))
    # # currentdmm.write("TRIG:SOUR IMM")
    # currentdmm.write("TRIG:SOUR EXT")
    # currentdmm.query('*OPC?')

    # T3DSO3000HD
    # Reset the oscilloscope and configure waveform data to be returned in double (binary) format.
    # oscilloscope.write("*RST; *CLS")
    # oscilloscope.write(":FORMat:DATA DOUBle")
    # Configure acquisition settings for a high-speed measurement.
    # oscilloscope.write(":ACQuire:AMODe FAST")         # Fast acquisition mode.
    # oscilloscope.write(":ACQuire:SRATe 5.00E9")         # Set sample rate (e.g., 5.00E9 samples/sec).

    # Configure external trigger on the falling (negative) edge.
    # oscilloscope.write(":TRIGger:SOURce EXTernal")      
    # oscilloscope.write(":TRIGger:TYPE EDGE")
    # oscilloscope.write(":TRIGger:EDGE:SLOPe NEGative")   # Specify falling edge trigger.
    # oscilloscope.write(":TRIGger:MODE NORMAL")

    # --- Channel Configurations ---
    # Channel 1: Measures device voltage (expected 0.8 to 1.2 V) using a 10:1 probe.
    # oscilloscope.write(":CHANnel1:PROBe VALue,1.00E+01")  # Configure Channel 1 with a 10:1 probe.
    # oscilloscope.write(":CHANnel1:SCALe 1.00E-01")         # Set vertical sensitivity (e.g., 100 mV/div).
    # oscilloscope.write(":CHANnel1:OFFSet -0.9")            # Subtract 0.9 V by setting an offset of –0.9 V.

    # Channels 2 and 3: Measure voltage across a 100 mΩ sense resistor using 1:1 probes.
    # oscilloscope.write(":CHANnel2:PROBe VALue,1.00E+00")  # Configure Channel 2 with a 1:1 probe.
    # oscilloscope.write(":CHANnel3:PROBe VALue,1.00E+00")  # Configure Channel 3 with a 1:1 probe.
    # oscilloscope.write(":CHANnel2:SCALe 1.00E-01")         # Set vertical sensitivity for Channel 2.
    # oscilloscope.write(":CHANnel3:SCALe 1.00E-01")         # Set vertical sensitivity for Channel 3.
    # oscilloscope.write(":CHANnel2:OFFSet -0.9")            # Subtract 0.9 V by setting an offset of –0.9 V.
    # oscilloscope.write(":CHANnel3:OFFSet -0.9")            # Subtract 0.9 V by setting an offset of –0.9 V.

    voltagedmm.write('INIT')
    currentdmm.write("TRIG:DIG:STIM EXT")
    time.sleep(0.1)
    
    # voltagedmm.write("*TRIG")
    # currentdmm.write("*TRIG")
    trigpin.off()

def PowerMeasureRead():
    trigpin.on()
    time.sleep(0.1) # Wait the data becomes ready to read
    # DMM7510
    print("CURRENT READ")
    currentdmm.query("*OPC?")
    currentdmm.write("FORM REAL")
    currentdmm.write('TRAC:DATA? 1, '+str(currentsample)+', "defbuffer1", READ')
    currentdata = currentdmm.read_binary_values(datatype = 'd', is_big_endian = False, data_points=currentsample)

    #34465A
    # currentdmm.write('FORM:DATA REAL,64')  # 転送フォーマットはBINARY
    # currentdmm.write('FETC?')  # 測定値の転送
    # currentdata = currentdmm.read_binary_values(datatype = 'd', is_big_endian = True, data_points=currentsample)
    print("VOLT READ")
    voltagedmm.query('*OPC?')
    voltagedmm.write('FORM:DATA REAL,64')  # 転送フォーマットはBINARY
    voltagedmm.write('DATA:REM? '+str(voltagesample))  # 測定値の転送
    # voltagedmm.write('FETC?')  # 測定値の転送
    # read_binary_values(datatype = 'd', ) は、float型 の list を返す
    voltdata = voltagedmm.read_binary_values(datatype = 'd', is_big_endian = True,data_points=voltagesample)
    if(max(voltdata) >= 1.2):
        print("VOLTAGE PROTECTION!")
        turnoff()
        exit(1)
    print("MIN VOL:" + str(min(voltdata)))
    print("MAX VOL:" + str(max(voltdata)))
    print("MAX CURR:" + str(max(currentdata)))
    print("MIN CURR:" + str(min(currentdata)))
    import numpy as np

    # Number of current samples
    n_current = len(currentdata)

    # The time indices for the current data.
    # We assume the current sample interval is dt (unknown, but cancels out) and the voltage sample interval is 5*dt.
    # So the current time points can be considered as: 0, 1, 2, ..., n_current-1
    t_current = np.arange(n_current)

    # Voltage is sampled 5 times slower.
    # Therefore, the voltage samples are at times: 0, 5, 10, ... up to len(voldata)*5 - 5.
    t_voltage = np.arange(len(voltdata)) * sampleratio

    # Interpolate the voltage values at each current time index.
    vol_interp = np.interp(t_current, t_voltage, voltdata)

    # Calculate power at each current sample: P = I * V.
    power = np.array(currentdata) * vol_interp
    # print([ float(x) for x in currentdmm.query(':TRAC:DATA? 1, 200, "defbuffer1"')[:-1].split(",")])
    # print(currentdmm.query(':TRAC:DATA? 1, 100, "defbuffer1", READ'))
    print("POWER:"+str(max(power)))
    return max(power), (voltdata, currentdata, power)

def xoshirotest(userefclk = True):
    if userefclk:
        spireset()
        spiconf.bits_per_word = 8

        ret = spiconf.xfer(8*[0x00])
        spiconfdummy.bits_per_word = 8
        ret = spiconfdummy.xfer(9*[0x00])
    ctrlcs.off()
    spictrl.bits_per_word = 15
    ret = spictrl.xfer([reverse_byte(cmd_seed),0x00]) #init async queue
    spictrl.bits_per_word = 8
    with open("testvectors/asconseed0.tv") as f:
        for s_line in f.read().splitlines():
            seedbytes = [reverse_byte(int(s_line[x:x+2],16)) for x in range(0, len(s_line), 2)]
            seedbytes.reverse()
            spictrl.xfer(seedbytes)
    with open("testvectors/asconseed1.tv") as f:
        for s_line in f.read().splitlines():
            seedbytes = [reverse_byte(int(s_line[x:x+2],16)) for x in range(0, len(s_line), 2)]
            seedbytes.reverse()
            spictrl.xfer(seedbytes)
    with open("testvectors/xoshiroseed.tv") as f:
        for s_line in f.read().splitlines():
            seedbytes = [reverse_byte(int(s_line[x:x+2],16)) for x in range(0, len(s_line), 2)]
            seedbytes.reverse()
            spictrl.xfer(seedbytes)
    ctrlcs.on()
    time.sleep(0.001)

    ctrlcs.off()
    spictrl.bits_per_word = 15
    ret = spictrl.xfer([0x00,0x00]) #init async queue
    spictrl.bits_per_word = 8
    ret = spictrl.xfer([reverse_byte(cmd_infifo)])
    with open("testvectors/input.tv") as f:
        for s_line in f.read().splitlines():
            s_filled = s_line.zfill(8)
            inputbytes = [reverse_byte(int(s_filled[x:x+2],16)) for x in range(0, len(s_filled), 2)]
            inputbytes.reverse()
            spictrl.xfer(inputbytes)
    ret = spictrl.xfer([0x00]) #make sure that the transfer will end correctly
    ctrlcs.on()
    time.sleep(0.001)

    diffcounter.write("INITiate")
    time.sleep(0.1) #needs time before trigger works

    ctrlcs.off()
    spictrl.bits_per_word = 15

    PowerMeasureStart()
    
    start = time.time()
    ret = spictrl.xfer([reverse_byte(cmd_run),0x00]) #init async queue

    ctrlcs.on()
    print("WAITING FIN")
    while True:
        if finpin.value:
            break
    end = time.time()
    print("DONE")
    time_diff = end - start
    print("by polling")
    print(time_diff)
    print("by triggering")
    time_diff=diffcounter.query("FETCh?")
    print(time_diff)

    power, dmmdata = PowerMeasureRead()

    ctrlcs.off()
    spictrl.bits_per_word = 15
    ret = spictrl.xfer([0x00,0x00]) #init async queue
    spictrl.bits_per_word = 8
    ret = spictrl.xfer([reverse_byte(cmd_out)])
    time.sleep(0.001)
    spictrl.bits_per_word = 32
    with open("testvectors/compress_output.tv") as f:
        for s_line in f.read().splitlines():
            ret = spictrl.xfer([0x00,0x00,0x00,0x00])
            s_filled = s_line.zfill(8)
            expectedbytes = [reverse_byte(int(s_filled[x:x+2],16)) for x in range(0, len(s_filled), 2)]
            if ret != expectedbytes:
                print(ret)
                print(expectedbytes)
            assert(ret == expectedbytes)
    print("XOSHIROTEST PASS")
    return [power, time_diff, dmmdata]

def precisetest():
    global bkraintt_bytes
    if bkraintt_bytes == []:
        print("Reading BK")
        with open('testvectors/bk.pickle', mode='br') as fi:
            bkraintt_bytes = pickle.load(fi)
    spireset()
    spiconf.open(1,2)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    config_str = str(hex(0b00000000_0000000_00000000_0000_0000_0000000000_000_0_0_0_0_0_111_0))[2:].zfill(16)
    config_bytes = [reverse_byte(int(config_str[x:x+2],16)) for x in range(0, len(config_str), 2)]
    config_bytes.reverse()
    ret = spiconf.xfer(config_bytes)
    spiconf.open(1,1)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(9*[0x00])

    ctrlcs.off()
    spictrl.bits_per_word = 15
    ret = spictrl.xfer([reverse_byte(cmd_infifo),0x00]) #init async queue
    spictrl.bits_per_word = 8
    with open("testvectors/input.tv") as f:
        for s_line in f.read().splitlines():
            s_filled = s_line.zfill(8)
            inputbytes = [reverse_byte(int(s_filled[x:x+2],16)) for x in range(0, len(s_filled), 2)]
            inputbytes.reverse()
            spictrl.xfer(inputbytes)
    ret = spictrl.xfer([0x00]) #make sure that the transfer will end correctly
    ctrlcs.on()
    time.sleep(0.001)


    nindex = 0
    while(nindex < lvl0n):
        print(nindex)
        PowerMeasureStart()
        for k in range(lvl1k+1):
            ctrlcs.off()
            spictrl.bits_per_word = 15
            ret = spictrl.xfer([0x00,0x00]) #init async queue
            spictrl.bits_per_word = 8
            ret = spictrl.xfer([reverse_byte(cmd_bkfifo[k])])
            spictrl.bits_per_word = wordbits-1
            for i in range(nindex,nindex+nstep):
                    for kindex in range(lvl1k+1):
                        for l in range(lvl1l):
                            for cycle in range(numcycle):
                                for bus in range(bknumbus//(lvl1k+1)):
                                    for word in range(nttwordsinbus): 
                                        ret = spictrl.xfer(bkraintt_bytes[k][i*(lvl1k+1)*lvl1l*lvl1N + kindex*lvl1l*lvl1N + l*lvl1N + cycle*bknumbus//(lvl1k+1)*nttwordsinbus+bus*nttwordsinbus+word])
            ctrlcs.on()
            time.sleep(0.001)
        ctrlcs.off()
        spictrl.bits_per_word = 15
        ret = spictrl.xfer([reverse_byte(cmd_runstep),0x00]) #init async queue
        spictrl.bits_per_word = 8
        spictrl.xfer([(1<<nstep)-1])
        ret = spictrl.xfer([0x00]) #make sure that the transfer will end correctly
        ctrlcs.on()
        time.sleep(0.001)
        nindex = nindex + nstep
        print("WAITING FIN")
        finpin.wait_for_active()
        PowerMeasureRead()
    print("DONE")


    ctrlcs.off()
    spictrl.bits_per_word = 15
    ret = spictrl.xfer([0x00,0x00]) #init async queue
    spictrl.bits_per_word = 8
    ret = spictrl.xfer([reverse_byte(cmd_out)])
    time.sleep(0.001)
    spictrl.bits_per_word = 32
    with open("testvectors/precise_output.tv") as f:
        for s_line in f.read().splitlines():
            ret = spictrl.xfer([0x00,0x00,0x00,0x00])
            s_filled = s_line.zfill(8)
            expectedbytes = [reverse_byte(int(s_filled[x:x+2],16)) for x in range(0, len(s_filled), 2)]
            assert(ret == expectedbytes)

    print("PRECISE TEST PASS")

def setPLL(PLLCOARSEDELAYBINARY_CODE, PLLMMDCLKDIV_RATIO, PIgain):
    # Initialize Pins
    pllbypasspin.on()
    spireset()

    spiconf.close()
    spiconf.open(1,2)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(config_bytes(PLLCOARSEDELAYBINARY_CODE, PLLMMDCLKDIV_RATIO, PIgain))
    # ret = spiconf.xfer(8*[0xff])
    spiconf.close()
    spiconf.open(1,1)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(9*[0x0])
    # print([reverse_byte(x) for x in ret])
    # print(repeat_reconstruct(ret))

    time.sleep(0.02) # Wait for Reset

    spiconf.close()
    spiconf.open(1,2)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(config_bytes(PLLCOARSEDELAYBINARY_CODE, PLLMMDCLKDIV_RATIO, PIgain,False))
    spiconf.close()
    spiconf.open(1,1)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(9*[0x0])

    time.sleep(0.5) # Wait Settling

    spiconf.close()
    spiconf.open(1,2)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(config_bytes(PLLCOARSEDELAYBINARY_CODE, PLLMMDCLKDIV_RATIO, PIgain,False))
    spiconf.close()
    spiconf.open(1,1)
    spiconf.max_speed_hz = spifreq
    spiconf.mode = 0b00
    spiconf.bits_per_word = 8
    ret = spiconf.xfer(9*[0x0])
    if repeat_reconstruct(ret) != config_bytes(PLLCOARSEDELAYBINARY_CODE, PLLMMDCLKDIV_RATIO, PIgain,False):
        print("PLL NOT LOCKED!")
        return False, 0
    else:
        print("PLL LOCKED")

    pllbypasspin.off()
    time.sleep(3)

    icvolt = powersupply.query('MEAS:VOLT? (@1)')
    pllvolt = powersupply.query('MEAS:VOLT? (@2)')
    # print("IC Voltage:" + icvolt)
    # print("PLL Volatage:" + pllvolt)
    # print(powersupply.query('MEAS:CURR? (@2)'))
    if float(icvolt) > 0.8 and float(pllvolt) > 0.8:
        freq=float(freqcounter.query("READ:FREQ?"))*512
        print("Frequency")
        print(str(freq)+"[Hz]")
        print(str(int(round(freq/1e6)))+"[MHz]")
        return True, freq
    else:
        print("IC Voltage:" + icvolt)
        print("PLL Volatage:" + pllvolt)
        raise ValueError("Too much power consumption!")


def autoPLLtune(codelist):
    previsPLL0V9 = codelist[0][3]
    if previsPLL0V9:
        code = 53
    else:
        code = 127
    for codeset in codelist:
        if previsPLL0V9 != codeset[3]:
            code += 15
        previsPLL0V9 = codeset[3]

        freqtolerance = 0.5
        if codeset[0]==13:
            freqtolerance=0.8
        while True:
            print("PLLMMDCLKDIV_RATIO:"+str(codeset[0]))
            print("PLLCOARSEDELAYBINARY_CODE: "+str(code))
            print("isPLL0V9: "+str(codeset[3]))
            time.sleep(0.1)
            pllbypasspin.on()
            ctrlcs.on()
            turnon(0,codeset[3])
            time.sleep(1) # Wait Power Up
            res = setPLL(code, codeset[0], codeset[2])
            turnoff()
            code -= 1
            if res[0]:
                if abs(20*codeset[0]-res[1]/1e6)<=freqtolerance:
                    codeset[1] = code + 1
                    break
                elif 20*codeset[0]-res[1]/1e6 < -freqtolerance:
                    # We should retry to lock
                    code += 5
    turnoff()
    return codelist
    

if __name__ == "__main__":
    functiongenerator.write('C1:BSWV WVTP,SQUARE,AMP,0.9,OFST,0.9,FRQ,'+str(clkfreq))
    pllbypasspin.on()
    functiongenerator.write('C1:OUTP ON')
    turnon()
    time.sleep(1)

    configuretest()

    # xoshirotest()
    # precisetest()

    minratio = 8
    pidchangeratio = 16
    maxratio = 17
    maxratio0V9 = 21
    icnumber = input("Type IC Number: ")
    print("Tune PLL")
    # codelist = [[i, 0, {"P": 14, "I": i//4-1}, False] for i in range(minratio,maxratio+1)] + [[i, 0, {"P": 13, "I": 15}, True] for i in range(19,maxratio0V9+1)]
    # 07, 02, 05
    codelist = [[i, 0, {"P": 13, "I": 2}, False] for i in range(minratio,maxratio+1)] + [[i, 0, {"P": 13, "I": 15}, True] for i in range(maxratio+1,maxratio0V9+1)]
    # 09
    if icnumber == "09":
        pidchangeratio = 17
        codelist = [[i, 0, {"P": 13, "I":1}, False] for i in range(minratio,pidchangeratio)] + [[i, 0, {"P": 13, "I": 2}, False] for i in range(pidchangeratio,maxratio+1)] + [[i, 0, {"P": 13, "I": 15}, True] for i in range(maxratio+1,maxratio0V9+1)]
    # codelist = [[i, 0, {"P": 13, "I": 14}, False] for i in range(minratio,maxratio+1)] + [[i, 0, {"P": 13, "I": 15}, True] for i in range(maxratio+1,maxratio0V9+1)]
    # codelist = [[i, 0, {"P": 12, "I": 13}, True] for i in range(maxratio+1,maxratio0V9+1)]
    codelist = autoPLLtune(codelist)
    # codelist = [[8, 110, {'P': 13, 'I': 2}, False], [9, 96, {'P': 13, 'I': 2}, False], [10, 85, {'P': 13, 'I': 2}, False], [11, 76, {'P': 13, 'I': 2}, False], [12, 70, {'P': 13, 'I': 3}, False], [13, 63, {'P': 13, 'I': 3}, False], [14, 57, {'P': 13, 'I': 3}, False], [15, 53, {'P': 13, 'I': 3}, False], [16, 49, {'P': 13, 'I': 4}, False], [17, 45, {'P': 13, 'I': 4}, False], [18, 42, {'P': 13, 'I': 4}, False], [19, 52, {'P': 13, 'I': 15}, True], [20, 48, {'P': 13, 'I': 15}, True], [21, 45, {'P': 13, 'I': 15}, True]]
    # codelist = [[8, 110, {'P': 13, 'I': 2}, False], [9, 97, {'P': 13, 'I': 2}, False], [10, 85, {'P': 13, 'I': 2}, False], [11, 76, {'P': 13, 'I': 2}, False], [12, 69, {'P': 13, 'I': 2}, False], [13, 63, {'P': 13, 'I': 2}, False], [14, 58, {'P': 13, 'I': 2}, False], [15, 52, {'P': 13, 'I': 2}, False], [16, 48, {'P': 13, 'I': 2}, False], [17, 46, {'P': 13, 'I': 2}, False], [18, 42, {'P': 13, 'I': 2}, False], [19, 52, {'P': 13, 'I': 15}, True], [20, 48, {'P': 13, 'I': 15}, True], [21, 45, {'P': 13, 'I': 15}, True]]
    # 09
    # codelist = [[8, 105, {'P': 13, 'I': 1}, False], [9, 92, {'P': 13, 'I': 1}, False], [10, 81, {'P': 13, 'I': 1}, False], [11, 73, {'P': 13, 'I': 1}, False], [12, 66, {'P': 13, 'I': 1}, False], [13, 60, {'P': 13, 'I': 1}, False], [14, 55, {'P': 13, 'I': 1}, False], [15, 50, {'P': 13, 'I': 1}, False], [16, 46, {'P': 13, 'I': 2}, False], [17, 43, {'P': 13, 'I': 2}, False], [18, 53, {'P': 13, 'I': 15}, True], [19, 50, {'P': 13, 'I': 15}, True], [20, 46, {'P': 13, 'I': 15}, True], [21, 44, {'P': 13, 'I': 15}, True]]
    print(codelist)

    print("START PLL TEST")
    # [Voltage, [Target Freq., [Latency, DMM Data, Frequency]]]
    voltrange = 15
    measured_data = [[0.8+d*0.01, [[i*20,[]] for i in range(minratio,maxratio0V9+1)]] for d in range(voltrange)]
    turnoff()
    for d in range(voltrange):
        for codepair in codelist:
            try:
                code = codepair[1]
                print("PLLMMDCLKDIV_RATIO:"+str(codepair[0]))
                print("PLLCOARSEDELAYBINARY_CODE: "+str(code))
                print("isPLL0V9: "+str(codepair[3]))
                targetfreq = 20*codepair[0]
                print("Voltage:"+str(0.8+0.01*d)+"V")
                print("Target FREQ:" + str(targetfreq)+"MHz")
                while True:
                    turnoff()
                    pllbypasspin.on()
                    ctrlcs.on()
                    time.sleep(0.1)
                    turnon(0.01*d-0.1,codepair[3])
                    time.sleep(1) # Wait Power Up
                    islock, freqval = setPLL(code,codepair[0],codepair[2])
                    freqtolerance = 0.5
                    if codepair[0]==13 or codepair == 15:
                        freqtolerance=0.7
                    if abs(targetfreq - freqval/1e6) > freqtolerance:
                        islock = False
                        if targetfreq - freqval/1e6 < -freqtolerance:
                            code += 3
                            time.sleep(3)
                        else:
                            if code < codepair[1] - 5:
                                code = codepair[1] + 5
                            else:
                                code -= 1
                    else:
                        break
                pllbypasspin.off()
            except:
                print(sys.exc_info())
                print("DID NOT LOCK!")
                turnoff()
                exit(1)
            try:
                expval = xoshirotest(False)
                measured_data[d][1][codepair[0]-minratio][1]=[expval[1],expval[2],freqval]
            except:
                print("Too High Freq!")
                print(sys.exc_info())
                if codepair[0] < 16:
                    print("Possibly too low")
                    continue
                break
    pllbypasspin.on()
    turnoff()
    with open(str(icnumber)+'_measured_data.pickle', mode='wb') as fo:
        pickle.dump(measured_data,fo)

    print("POWER MEAS XOSHIRO")
    power_data = []
    turnoff()
    for codepair in codelist:
        code = codepair[1]
        pllbypasspin.on()
        ctrlcs.on()
        turnon()
        time.sleep(1) # Wait Power Up
        targetfreq = 20*codepair[0]
        print("Target FREQ:" + str(targetfreq)+"MHz")
        while True:
            turnoff()
            pllbypasspin.on()
            ctrlcs.on()
            turnon()
            time.sleep(1) # Wait Power Up
            islock, freqval = setPLL(code,codepair[0])
            if round(abs(targetfreq - freqval/1e6)) != 0:
                islock = False
                if targetfreq - freqval/1e6 < -1:
                    code += 3
                else:
                    code -= 1
            else:
                break
        pllbypasspin.off()
        try:
            expval = xoshirotest(False)
            power_data.append([freqval,expval])
        except:
            print("Too High Freq!")
            break
        pllbypasspin.on()
        turnoff()
    with open('power_data.pickle', mode='wb') as fo:
        pickle.dump(power_data,fo)

    print("SHMOO MEAS")
    shmoo_data = []
    # turnoff()
    for d in range(13):
        volshmoo = []
        turnon(0.01*d-0.1)
        time.sleep(5)
        # powersupply.write('APPL CH1,'+str(0.8+0.01*d)+',3.0')
        for i in range(60,0,-1):
            print((d,i))
            freqval = setPLL(i)
            try:
                expval = xoshirotest(False)
                volshmoo.append([freqval,expval])
            except:
                print("Too High Freq!")
                turnoff()
                break
            pllbypasspin.on()
        if volshmoo != []:
            shmoo_data.append([0.8+0.01*d,volshmoo])
    with open('shmoo_data.pickle', mode='wb') as fo:
        pickle.dump(shmoo_data,fo)

    turnoff()
