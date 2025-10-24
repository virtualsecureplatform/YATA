#!/bin/python3
radixbit = 3
r = 2**(radixbit-1)
k=5
K=k**r
i = 5
N=K*2**(r*i)+1

from math import log,ceil
from sympy import mod_inverse
from sympy.ntheory.factor_ import factorint

R= 2**ceil(log(N)/log(2))
# R = 2**32
N2 = mod_inverse(R-N,R)
# N2 = mod_inverse(N,R)
R2 = mod_inverse(R,N)
print(ceil(log(N)/log(2)))
print(N)
print(bin(N))
print(N2)
print(bin(N2))
print(bin(N-N2))
print(bin(N*N2))
print(bin((N*N2)%R))
print(bin(round(R**2/(2*N))))
print("R2")
print(R2)
print(bin(R2))