The main Test\&Measurement script is `oneshottest.py`.

## Dependencies

Install required Python packages:

```bash
pip install -r requirements.txt
```

Required packages:
- gpiozero
- matplotlib
- numpy
- pyvisa
- seaborn
- spidev

`testvectors/` directory includes the fixed dataset (input ciphertexts and evaluation key) we used for performance measurement. 

The measured data is located in `log/` directory.
If you installed bzip2 on your machine, running `decompress_pickles.sh` decompresses them.  

After decompression, by running `measured_plot.py`, you can reproduce our Fig. 4 and 6. Because we retouched Figure 6 for readability, you may notice some differences, but this is expected. 
