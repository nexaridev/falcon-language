use std::time::Instant;
fn main(){let t=Instant::now();let mut total=0i64;for _ in 0..20{for i in 0..100000i64{let x=std::hint::black_box(i);total+=(x*17+13)%1009;}}println!("{:.3} {}",t.elapsed().as_secs_f64()*1000.0/20.0,total/20);}
