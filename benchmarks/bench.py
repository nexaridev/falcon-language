import time
n=1500
a=[(i*7919)%n for i in range(n)]
t=time.perf_counter();a.sort();ms=(time.perf_counter()-t)*1000
print(f"{ms:.3f} {sum(a)}")
total=0;t=time.perf_counter()
for i in range(100000): total+=(i*17+13)%1009
print(f"{(time.perf_counter()-t)*1000:.3f} {total}")
