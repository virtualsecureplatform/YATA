#!/bin/python3 
K = 5
o = 4
r = 8
w = K*(2**o)
Wlist = [1 , w, w**2, w**3, 1, w, w**2, w**3]
P = K**(r//2)*2**(r//2*o)+1

sumlist = [sum([Wlist[i*j%r] for j in range(r)]) for i in range(r)]
print(sumlist)
print(max(sumlist)*(P-1))
print(P**2//2)