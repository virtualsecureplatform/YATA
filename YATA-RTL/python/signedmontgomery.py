#!/bin/python3

import numpy as np

Word = np.uint32
SWord = np.int32
DoubleWord = np.uint64
DoubleSWord = np.int64
k = 5
radixbit = 3
radixs2 = 1 << (radixbit - 1)
K = np.power(k, radixs2, dtype=Word)
shiftunit = 5
shiftamount = radixs2 * shiftunit
shiftval = 1 << shiftamount
wordbits = 32
wordmask = (1 << wordbits) - 1
P = (K << shiftamount) + 1
W = 11

def SREDC(a: DoubleWord) -> SWord:
    a0 = np.uint32(a) & wordmask
    a1 = a >> wordbits
    m = np.int32(-((a0 * K) * shiftval) + a0)
    t1 = np.int32((((m * K) << shiftamount) + m) >> wordbits)
    return a1 - t1

R = 2**32 % P

import random

print(P)
for i in range(1000):
    a = random.randint(-P,P)
    b = random.randint(-2**31,2**31)
    bR = b*R%P
    res = SREDC(a*bR)
    if res < 0:
        res = res + P
    if res != a*b%P:
        print("ERROR")
        exit(1)