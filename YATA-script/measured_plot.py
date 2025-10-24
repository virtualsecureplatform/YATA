import pickle

import seaborn as sns
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import matplotlib.patches as patches
from matplotlib.colors import ListedColormap
import matplotlib.colors as mcolors
import numpy as np
import math

def power_plot(icnumber, figsize):
  with open(icnumber+'_measured_data.pickle', mode='br') as fi:
    measured_data = pickle.load(fi)

  sns.set()
  # plt.yscale('log')
  fig, ax0 = plt.subplots(figsize=figsize)
  ax1 = ax0.twinx()

  data = measured_data[-1][1]

  freq = []
  power = []
  latency = []
  for result in data:
    if result[1] != []:
      freq.append(float(result[1][2])/1e6)
      power.append(max(result[1][1][2]))
      latency.append(float(result[1][0])*1e3)

  p2, = ax0.plot(freq, power, 'C2o--', label='Power (0V92)')
  p3, = ax1.plot(freq, latency, 'C3o-', label='Latency (0V94)')

  data = measured_data[10][1]

  freq = []
  power = []
  latency = []
  for result in data:
    if result[1] != []:
      freq.append(float(result[1][2])/1e6)
      power.append(max(result[1][1][2]))
      latency.append(float(result[1][0])*1e3)

  p0, = ax0.plot(freq, power, 'C0x--', label='Power (0V9)')
  p1, = ax1.plot(freq, latency, 'C1x-', label='Latency (0V9)')

  ax0.set_xlabel('Frequency [MHz]')
  ax0.set_ylabel('Power [W]')
  ax1.set_ylabel('Latency [ms]')

  # Title will be set by LaTeX side
  # ax0.set_title('Power-Latency plot')

  lgd = ax1.legend(handles=[p0,p1,p2,p3], loc='upper center', facecolor='white', framealpha=1)
  ax0.xaxis.grid(True, linestyle='-', linewidth=0.7, color='black')
  # 左軸（power側）は点線のグリッド
  ax0.yaxis.grid(True, linestyle='--', linewidth=0.7, color='black')
  # 右軸（latency側）は実線のグリッド
  ax1.yaxis.grid(True, linestyle='-', linewidth=0.7, color='black')


  # plt.show()
  # plt.gcf().set_size_inches(10, 5)
  plt.rcParams["font.size"] = 18
  plt.tight_layout()
  plt.savefig("power_plot.jpg", dpi=300)
  plt.savefig("power_plot.pdf")

###############################################################################################

def shmoo_plot(chip_ids, figsize, hatch_fail='xx', hatch_mixed='', hatch_pass='//'):
    # Load measured data for each chip and store in a list
  chips_data = []
  for ic in chip_ids:
      with open(f"{ic}_measured_data.pickle", mode='br') as fi:
          data = pickle.load(fi)
          chips_data.append(data)
  """
  Generate a combined shmoo plot for multiple chips.
  
  For each cell (voltage/frequency point):
    - All chips pass --> cell colored green.
    - All chips fail --> cell colored red.
    - Mixed results --> cell colored with a yellow gradation based on the fraction of passing chips.
    
  Also overlays the number of passing chips in the center of each cell.
  """
  sns.set(style="whitegrid")
  
  # Determine grid dimensions from the first chip's data (assumed common to all chips)
  first_chip = chips_data[0]
  nrows = len(first_chip)
  # ncols = [1 for data in first_chip[-1][1] if data[1] != []]
  ncols = np.where(np.array([data[1] != [] for data in first_chip[-1][1]]))[0][-1]+1
  
  # Create axis labels for rows and columns.
  # Here we assume each row corresponds to a voltage level.
  row_labels = [f"{0.8 + 0.01 * d:.2f}" for d in range(nrows)]
  col_labels = [first_chip[-1][1][i][0] for i in range(ncols)]

  total_chips = len(chips_data)  # Total number of chips
  # for i in range(total_chips):
    # print(f"Chip {i+1}/{total_chips}: {chip_ids[i]}")
    # print(np.where(np.array([data[1] != [] for data in chips_data[i][-1][1]]))[0][-1]+1)
  
  fig, ax = plt.subplots(figsize=figsize)
  
  # Get the RGB values for red and green.
  fail_rgb = np.array(mcolors.to_rgb("red"))
  pass_rgb = np.array(mcolors.to_rgb("green"))
  
  # Loop over each cell in the grid.
  for i in range(nrows):
      for j in range(ncols):
          # Determine the pass (True) or fail (False) for each chip in this cell.
          results = [chip[i][1][j][1] != [] for chip in chips_data]
          pass_count = sum(results)
          frac = pass_count / total_chips  # Fraction of chips passing

          # Determine overall status of cell and the corresponding color:
          if pass_count == total_chips:
              overall = "pass"
              color = pass_rgb
              hatch = hatch_pass
          elif pass_count == 0:
              overall = "fail"
              color = fail_rgb
              hatch = hatch_fail
          else:
              overall = "mixed"
              # Compute the fraction (between 0 and 1) of passing chips.
              frac = pass_count / total_chips
              # Interpolate between two yellow shades:
              # For low fraction, use a lighter yellow; for higher fraction, use pure yellow.
              color_rgb = (1 - frac) * fail_rgb + frac * pass_rgb
              color = color_rgb.tolist()
              hatch = hatch_mixed
          # Draw each cell as a rectangle with the computed color and hatch pattern.
          rect = patches.Rectangle((j, i), 1, 1,
                                    facecolor=color,
                                    hatch=hatch,
                                    edgecolor='black')
          ax.add_patch(rect)
          
          # Overlay the number of passing chips at the center of the cell.
          ax.text(j + 0.5, i + 0.5, f"{pass_count}", 
                  ha='center', va='center', color='black', fontsize=12)
    
    
  # Set axis limits so that each cell is fully visible
  ax.set_xlim(0, ncols)
  ax.set_ylim(0, nrows)
  
  # Place ticks at the center of each cell
  ax.set_xticks(np.arange(0.5, ncols, 1))
  ax.set_xticklabels(col_labels, rotation=45, ha='right')
  ax.set_yticks(np.arange(0.5, nrows, 1))
  ax.set_yticklabels(row_labels)
  
  # Disable grid lines for both axes
  ax.grid(False)
  
  # Add labels and title
  plt.xlabel("Frequency [MHz]")
  plt.ylabel("Voltage [V]")
  plt.rcParams["font.size"] = 18
  plt.tight_layout()
  plt.savefig("shmoo_plot.jpg", dpi=300)
  plt.savefig("shmoo_plot.pdf")

# power_plot("07", figsize=(7,4))
shmoo_plot(["07","02","05","09","10"] , figsize=(7,4))
# shmoo_plot(["07","02","05","10"] , figsize=(7,4))