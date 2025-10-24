import pickle

bkraintt_bytes = [[],[],[]]

def reverse_26_bit_integer(n):
    # Ensure the number is within the 27-bit range
    if n < 0 or n >= (1 << 26):
        raise ValueError("The number must be a non-negative integer less than 2^27")

    # Convert the number to a 27-bit binary string
    binary_str = f"{n:026b}"

    # Reverse the binary string
    reversed_binary_str = binary_str[::-1]

    # Convert the reversed binary string back to an integer
    inverted_integer = int(reversed_binary_str, 2)

    return inverted_integer

for i in range(3):
    with open("testvectors/bkraintt"+str(i)+".tv") as f:
        for s_line in f.read().splitlines():
            bkword = reverse_26_bit_integer(int(s_line,16))
            bkbytes = [(bkword>>(8*x)) & ((1<<8)-1) for x in range(4)]
            bkraintt_bytes[i].append(bkbytes)

with open('testvectors/bk.pickle', mode='wb') as fo:
  pickle.dump(bkraintt_bytes, fo)