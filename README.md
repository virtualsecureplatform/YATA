This repository is the artifact for the TCHES2026 paper; YATA: Yet Another TFHE Accelerator with Key Compression and Radix-8 NTT

# Directories
- YATA-RTL: RTL codes excluding NDA related things like SRAM IP and PLL. This also contains RTL-level simulations and some Python scripts we used for the design. 
- YATA-PCB: The KiCAD project for the evaluation board.
- YATA-script: Test\&Measurement and making graph scripts, we used to make the evaluation section of our paper. This also contains Equipment.md, the description of the measurement equipment we used.

# LICENSE
RTL is basically licensed under AGPLv3, except for the AsyncQueue, which originated from Rocketchip. 

PCB and scripts are under MIT. 

# Quick Start For Artifact Reviewers
Since reproducing our evaluation entirely requires physical access to our fabricated chip, we only hope to ensure the availability of our artifact. 

## Cloning
Because YATA-RTL includes subprojects, please clone with `--recursive` flag like:

`git clone --recursive https://github.com/virtualsecureplatform/YATA.git`

## YATA-RTL
For 'YATA-RTL', you can find our RTL codes under 'chise/src/main/'. 
To compile our Chisel3 code to Verilog and run tests with Verilator, please install Apptainer or Singularity, then build and run yata-rtl.def. 

## YATA-PCB
You can open the design using KiCAD 9 or later. We also provide the required footprint library under `Library.pretty` and the Gerber file under `jlcpcb`. We believe that if the reviewer can open the design and check the availability of libraries and gerber file, it is enough. 

## YATA-scritpt
Though the main script, `oneshottest.py`, is included,  this requires all the equipment we used to check executability. Hence, we believe just checking the availability is enough. 
`testvectors` includes some data required for measurement using `oneshottest.py`, like input ciphertexts and evaluation keys. 

The important part is that `logs` directory includes our measured data compressed in bzip2. If you installed bzip2 on your machine, running `decompress_pickles.sh` decompresses them.  
After decompression, by running `measured_plot.py`, you can reproduce our Fig. 4 and 6. Because we retouched Figure 6 for readability, you may notice some differences, but this is expected. 
