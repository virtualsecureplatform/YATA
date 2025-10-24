#!/bin/python3
import pyvisa

rm = pyvisa.ResourceManager()
powersupply = rm.open_resource('TCPIP::10.0.20.72::INSTR')
vddio = rm.open_resource('USB0::1003::8293::HEWLETT-PACKARD_E3631A_0_3.0-6.0-2.0::0::INSTR')
functiongenerator = rm.open_resource('USB0::1535::2593::LCRY2362C01679::0::INSTR')

powersupply.write('OUTP OFF,(@1)')
powersupply.write('OUTP OFF,(@2)')
powersupply.write('VOLT:PROT:CLE (@1)')
powersupply.write('VOLT:PROT:CLE (@2)')
vddio.write("OUTPut OFF")
functiongenerator.write('C1:OUTP OFF')
