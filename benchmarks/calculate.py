import time
t=time.perf_counter()
total=0
for _ in range(20):
    for i in range(100000): total+=(i*17+13)%1009
print(f"{(time.perf_counter()-t)*1000/20:.3f} {total//20}")
