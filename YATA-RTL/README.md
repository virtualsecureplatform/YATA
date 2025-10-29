# YATA-RTL

This project provides a Singularity container for building and testing the RTL coedes for YATA, which includes Chisel hardware design, Verilator simulation, and TFHEpp cryptographic library integration.

## Overview

The YATA-RTL project combines:
- **Chisel** - Hardware construction language embedded in Scala
- **Verilator** - Fast free Verilog HDL simulator
- **TFHEpp** - C++ implementation of TFHE (Fast Fully Homomorphic Encryption over the Torus)
- **CMake/Ninja** - Modern build system with Clang/LLD toolchain

## Project Structure

```
YATA-RTL/
├── chisel/                   # Chisel hardware designs
│   ├── src/
│   │   ├── main/scala/       # Chisel source files
│   │   └── test/cpp/         # C++ test files
│   ├── build.sbt             # Scala build configuration
│   └── CMakeLists.txt
├── thirdparties/
│   ├── ascon-hardware/       # ASCON's reference implementation for testing 
│   └── TFHEpp/               # TFHE library for CPU
├── CMakeLists.txt            # Top-level CMake config
├── yata-rtl.def              # Singularity definition file
├── python/                   # Python scripts we used for determing some design decisions 
└── README.md                 # This file
```

### Chisel Source Files (`chisel/src/main/scala/`)

#### Core Configuration
- **`Config.scala`** - Central configuration file defining all TFHE parameters (security parameters n, N, k, l), hardware settings (bus widths, memory depths), NTT parameters, and arithmetic constants for the cryptographic operations.

#### Cryptographic Core Modules
- **`BlindRotate.scala`** - Implements TFHE blind rotation operation with test vector rotation and memory management. Contains `RotatedTestVector` for generating rotated test polynomials and `BlindRotateMemory` for efficient storage and access of blind rotation data.

- **`ExternalProduct.scala`** - Performs external product operations for TFHE. Includes `Decomposition` module that decomposes ciphertexts into digits.

- **`KeyCompression.scala`** - Handles bootstrapping key compression using PRNG-based techniques. Integrates multiple ASCON PRNG instances to generate compressed bootstrapping keys with reduced storage requirements.

- **`PolynomialMulByXai.scala`** - Implements polynomial multiplication by X^ai operation, a critical component in TFHE polynomial arithmetic for rotating polynomials by arbitrary amounts.

#### NTT and Arithmetic Modules
- **`NTT.scala`** - Number Theoretic Transform implementation with `DoubleLbuffer` for efficient double buffering and pipelined NTT operations supporting high-throughput polynomial multiplication.

- **`INTorus.scala`** - Arithmetic operations over modulo P. Includes `INTorusADD`, `INTorusSUB`, and `INTorusSREDC` for addition, subtraction, and Montgomery reduction.

- **`ButterflyADD.scala`** - Butterfly operations for NTT/INTT computations. Contains `ButterflyBothMod` and `ButterflyAddMod` for parallel addition and subtraction used in NTT butterflies.

#### Random Number Generation
- **`xoshiro.scala`** - Xoshiro128++ PRNG implementation for generating pseudorandom numbers with 128-bit state, used for non-nonce part of the evaluation key in TFHE.

- **`ascon.scala`** - ASCON cryptographic permutation components including S-box (`ASCONSbox`), linear layer (`ASCONLinear`), and full permutation (`ASCONPermuitation`) for PRNG operations.

#### Communication Interfaces
- **`AXI4.scala`** - AXI4 and AXI4-Stream interface definitions. Includes `AXI4Manager`, `AXI4StreamManager`, and `AXI4StreamSubordinate` for standard AMBA communication protocols.

- **`SPI.scala`** - SPI interface for configuration and control. Contains `SPIConfigModule` for runtime parameter configuration and status monitoring via SPI protocol.

- **`ChangeSizer.scala`** - Data width conversion modules. Includes `UpSizer` and `DownSizer` for converting between different bus widths in the data path.

#### AsyncQueue (Clock Domain Crossing, From RocketChip)
- **`AsyncQueue/AsyncQueue.scala`** - Main asynchronous FIFO queue for safe clock domain crossing with configurable depth and synchronization stages.

- **`AsyncQueue/AsyncResetReg.scala`** - Reset synchronization primitives for handling reset signals across clock domains.

- **`AsyncQueue/SynchronizerReg.scala`** - Register-based synchronizers for CDC (Clock Domain Crossing) with configurable synchronization stages.

- **`AsyncQueue/Crossing.scala`** - High-level crossing utilities combining AsyncQueue with other CDC primitives.

- **`AsyncQueue/ShiftReg.scala`** - Shift register implementations for delay lines and synchronization.

- **`AsyncQueue/Counter.scala`** - Gray code counters for AsyncQueue pointer management across clock domains.

- **`AsyncQueue/BundleMap.scala`** - Utilities for mapping bundles across AsyncQueue interfaces.

- **`AsyncQueue/CompileOptions.scala`** - Compilation options and parameters for AsyncQueue modules.

- **`AsyncQueue/package.scala`** - Package object with utility functions and implicit conversions for AsyncQueue.

#### Utility Modules
- **`Util.scala`** - Memory utility components including `RWSmem` (single-port SRAM), `WFTPSAmem` (write-first two-port SRAM), and `RWDmem` (dual-port SRAM) for various memory access patterns.

## Prerequisites

- Singularity/Apptainer installed on your system
- Sudo access (sometimes required for building the container)
- At least 2GB of free disk space

### Installing Apptainer

On Ubuntu/Debian:
```bash
sudo apt-get update
sudo add-apt-repository -y ppa:apptainer/ppa
sudo apt update
sudo apt-get install -y apptainer
```

For other systems, see [Apptainer Installation Guide](https://apptainer.org/docs/admin/main/installation.html).

## Building the Container

Build the Singularity container from the definition file:

```bash
sudo singularity build yata-rtl.sif yata-rtl.def
```

This process takes approximately 10-15 minutes and will:
- Install Ubuntu 24.04 base system
- Install Java and sbt (Scala Build Tool)
- Install CMake 3.31+ from Kitware
- Build Verilator 5.x from source
- Install Clang, LLD, Ninja, and other build tools
- Install TFHEpp dependencies

The resulting container image (`yata-rtl.sif`) will be approximately 987MB.

## Usage

### Running the Full Build Pipeline

Execute the complete build and test pipeline:

```bash
singularity run --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif
```

This will:
1. Run `sbt` to compile Chisel/Scala code and generate Verilog
2. Configure the project with CMake
3. Build with Ninja
4. Execute the test program

### Running Individual Commands

Execute specific commands inside the container:

```bash
# Run sbt only
singularity exec --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif \
  bash -c "cd /home/ubuntu/sources/YATA/YATA-RTL/chisel && sbt run"

# Build with CMake and Ninja
singularity exec --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif \
  bash -c "cd /home/ubuntu/sources/YATA/YATA-RTL && cmake . -B build -G Ninja -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_C_COMPILER=clang -DCMAKE_LINKER_TYPE=LLD && cd build && ninja"

# Run tests
singularity exec --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif \
  bash -c "cd /home/ubuntu/sources/YATA/YATA-RTL/build && ./chisel/src/test/cpp/cpptest"
```

### Interactive Shell

Launch an interactive shell inside the container:

```bash
singularity shell --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif
```

## Container Contents

The container includes:

### Build Tools
- **CMake** 3.31+ (from Kitware repository)
- **Ninja** build system
- **Clang** 18.1.3 (C/C++ compiler)
- **LLD** (LLVM linker)
- **GCC** 13 (GNU compiler collection)

### Development Tools
- **Java** 11 (OpenJDK)
- **sbt** 1.11.7 (Scala Build Tool)
- **Verilator** 5.040 (built from source)
- **Git**

### Libraries
- **TFHEpp** dependencies:
- **Verilator** dependencies

## Build Output

After a successful build, you will find:

- **Generated Verilog**: `chisel/AXISBRWrapper.v`
- **Build directory**: `build/` (contains all compiled objects)
- **Test executable**: `build/chisel/src/test/cpp/cpptest` (~43MB)
- **Static libraries**:
  - `build/thirdparties/TFHEpp/src/libtfhe++.a`
  - `build/thirdparties/TFHEpp/thirdparties/randen/libranden.a`
  - `build/thirdparties/TFHEpp/thirdparties/spqlios/libspqlios.a`


## Troubleshooting

### Container Build Fails

If the container build fails:
1. Ensure you have sudo access
2. Check available disk space (`df -h`)
3. Try cleaning up old containers: `singularity cache clean`

### sbt Compilation Warnings

Deprecation warnings from Chisel 3.6 are expected and do not affect functionality. The project uses Chisel 3.6 compatibility layer.

### Linker Warnings

Warnings about `_ExtInt` being deprecated are from TFHEpp and do not affect the build.

### Permission Issues

If you encounter permission errors, ensure the bind mount path is correct and accessible:
```bash
singularity exec --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif ls -la /home/ubuntu/sources/YATA/YATA-RTL
```

## Development Workflow

1. **Modify Chisel sources** in `chisel/src/main/scala/`
2. **Generate Verilog**:
   ```bash
   singularity exec --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif \
     bash -c "cd /home/ubuntu/sources/YATA/YATA-RTL/chisel && sbt run"
   ```
3. **Build and test**:
   ```bash
   singularity run --bind $(pwd):/home/ubuntu/sources/YATA/YATA-RTL yata-rtl.sif
   ```

## Performance Notes

- **First build**: Takes ~5-10 minutes (compiles Scala, generates Verilog, compiles C++)
- **Incremental builds**: Much faster, only recompiles changed files
- **Parallel compilation**: Verilator uses 12 threads, Ninja uses all available cores

## License

See the LICENSE file for license information.

## References

- [Chisel](https://www.chisel-lang.org/) - Hardware construction language
- [Verilator](https://verilator.org/) - Fast Verilog simulator
- [TFHEpp](https://github.com/virtualsecureplatform/TFHEpp) - TFHE implementation
- [Singularity](https://sylabs.io/singularity/) - Container platform
