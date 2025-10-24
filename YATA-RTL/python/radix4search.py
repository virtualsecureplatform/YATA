#!/bin/python3
import sympy
from math import log
r = 4
b = 5
k=b**r
# k=5**4
# k=3**4

for i in range(32):
    N=k*2**(r*i)+1
    if sympy.isprime(N):
        print(i)
        print(N)
        print(log(N)/log(2))

from sympy.ntheory import is_primitive_root
id = 4
P= k*2**(r*id)+1
print(P)
C= b*2**id
for W in range(2,P):
    if(is_primitive_root(W,P)):
        p = W**k%P
        for i in range(r*id - 3):
            p = p * p % P
        print(p)
        if(C == p):
            print("Answer")
            print(W)
            break