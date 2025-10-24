# Measurement Settings
This subsection outlines the measurement settings, including the equipment, voltage settings, and procedure we used for our evaluation.

---
## Equipment

The experimental setup used a Raspberry Pi 5 as the host computer, which communicated with the YATA chip via SPI and GPIO interfaces.
A Texas Instruments SN74AXC4T774-Q1 level shifter was used on the test board to interface the Raspberry Pi's 3.3 V I/O with YATA's 1.8 V I/O.

Power was supplied by KeySight E36233A (for the compute core and PLL) and E3631A (for I/O pins).
To ensure accurate voltage delivery to the compute core despite observed on-chip voltage drop, the E36233A's remote sense feature was connected to an on-chip sense point.

Measurements were performed using the following instruments:
* **Voltage & Current:** KeySight 34465A for monitoring core supply voltage, and Tektronix DMM7510 for measuring core current with 1 µs interval.
* **Latency:** KeySight 53230A Time Interval Counter measured latency, which is defined as the time from the start of the SPI 'RUN' command's SCLK to the assertion of the *fin* signal.
* **Frequency:** A KeySight 53132A Frequency Counter monitored the divided PLL clock frequency (PLLCLK\_DIV).
* **Reference clock:** A Teledyne LeCroy WaveStation 2052 provided a 20 MHz reference clock.

The Raspberry Pi 5 automated the entire experiment, via standard instrument interfaces (LXI, USB, and GPIB using a [UsbGpib](https://github.com/xyphro/UsbGpib) adapter). Instrument control and data acquisition were managed using PyVISA.

---
## Voltage Settings

Nominal operating voltages for YATA chip are: $0.9\,\text{V}$ for the compute core, $0.8\,\text{V}$ for the PLL, and $1.8\,\text{V}$ for I/O.
To explore frequency and stability trade-offs, we varied the core and PLL voltages:
PLL voltage was increased to $0.9\,\text{V}$ when operating above $340\,\text{MHz}$ for improved PLL stability.
Core voltage was swept from $0.8\,\text{V}$ to $0.94\,\text{V}$ in $0.1\,\text{V}$ steps. The upper limit was chosen as the highest voltage without causing degradation or thermal damage to the chip.