#!/bin/bash

# Script to decompress all pickle files
# Handles both single files and split files that need to be concatenated

set -e  # Exit on error

echo "Starting decompression of pickle files..."
echo

# Decompress single pickle files
for file in 02_measured_data.pickle.bz2 05_measured_data.pickle.bz2 09_measured_data.pickle.bz2 10_measured_data.pickle.bz2; do
    if [ -f "$file" ]; then
        echo "Decompressing $file..."
        bunzip2 -v "$file"
        echo "✓ Done: ${file%.bz2}"
    fi
done

echo

# Handle split 07_measured_data.pickle parts
if ls 07_measured_data.pickle.part*.bz2 1> /dev/null 2>&1; then
    echo "Found split 07_measured_data.pickle files..."
    echo "Decompressing parts..."

    # Decompress all parts
    bunzip2 -v 07_measured_data.pickle.part*.bz2

    echo "Concatenating split parts back together..."
    cat 07_measured_data.pickle.parta* > 07_measured_data.pickle

    echo "Cleaning up split parts..."
    rm 07_measured_data.pickle.parta*

    echo "✓ Done: 07_measured_data.pickle"
fi

echo
echo "All pickle files have been successfully decompressed!"
echo
echo "Final files:"
ls -lh *_measured_data.pickle
