//! Pure-Rust timing of release classification, without JNI, on the exact streams the Kotlin
//! `ProviderProcessingBenchmarkTest` generated (it writes `target/bench/provider_streams_<n>.json`).
//! Run that benchmark first, then:
//! `cargo test --release -p nuvio-core --test bench -- --ignored --nocapture`

mod common;

use common::owned_stream;
use nuvio_core::release_facts::classify;
use serde_json::Value;
use std::hint::black_box;
use std::time::{Duration, Instant};

#[test]
#[ignore = "benchmark; needs inputs from the Kotlin ProviderProcessingBenchmarkTest"]
fn classify_without_jni() {
    let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../target/bench");
    println!("streams,classify_pure_rust_median_us,p10_us,p90_us");
    for size in [50, 150, 400] {
        let path = format!("{dir}/provider_streams_{size}.json");
        let Ok(raw) = std::fs::read_to_string(&path) else {
            println!("{size},missing {path}");
            continue;
        };
        let entries: Vec<Value> = serde_json::from_str(&raw).expect("valid benchmark JSON");
        let streams: Vec<_> = entries.iter().map(|e| owned_stream(&e["input"])).collect();
        let run = || {
            streams
                .iter()
                .map(|s| black_box(classify(&s.input())).languages.len())
                .sum::<usize>()
        };
        let warm_until = Instant::now() + Duration::from_millis(1500);
        while Instant::now() < warm_until {
            black_box(run());
        }
        let mut samples: Vec<f64> = (0..41)
            .map(|_| {
                let start = Instant::now();
                black_box(run());
                start.elapsed().as_secs_f64() * 1e6
            })
            .collect();
        samples.sort_by(f64::total_cmp);
        println!(
            "{size},{:.1},{:.1},{:.1}",
            samples[20], samples[4], samples[36]
        );
    }
}
