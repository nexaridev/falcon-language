import java.util.Arrays;
public class Sort {
  public static void main(String[] args) {
    int n=1500; long[] a=new long[n];
    for(int i=0;i<n;i++) a[i]=(i*7919)%n;
    for(int warm=0;warm<2000;warm++){long[] copy=a.clone();Arrays.sort(copy);}
    long start=System.nanoTime(); for(int round=0;round<500;round++){long[] copy=a.clone();Arrays.sort(copy);} double ms=(System.nanoTime()-start)/1e6/500.0;
    long sum=0; for(long v:a)sum+=v; System.out.printf(java.util.Locale.ROOT,"%.3f %d%n",ms,sum);
  }
}
