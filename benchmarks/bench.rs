use std::time::Instant;
fn main(){
 let n=1500usize;let mut a:Vec<i64>=(0..n).map(|i|((i*7919)%n) as i64).collect();
 let t=Instant::now();a.sort_unstable();println!("{:.3} {}",t.elapsed().as_secs_f64()*1000.0,a.iter().sum::<i64>());
 let t=Instant::now();let mut total=0i64;for i in 0..100000i64{let x=std::hint::black_box(i);total+=(x*17+13)%1009;}println!("{:.3} {}",t.elapsed().as_secs_f64()*1000.0,total);
}
