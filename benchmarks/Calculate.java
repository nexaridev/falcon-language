public class Calculate {
  public static void main(String[] args) {
    long warm=0; for(int r=0;r<20;r++)for(int i=0;i<100000;i++)warm+=(i*17L+13)%1009;
    long total=0; long start=System.nanoTime();
    for(int r=0;r<20;r++)for(int i=0;i<100000;i++) total+=(i*17L+13)%1009;
    System.out.printf(java.util.Locale.ROOT,"%.3f %d%n",(System.nanoTime()-start)/1e6/20,total/20);
  }
}
