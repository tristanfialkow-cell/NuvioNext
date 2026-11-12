//! Pure-Rust timing of release classification, without JNI, on the exact streams the Kotlin
//! `ProviderProcessingBenchmarkTest` classified (it writes `target/bench/provider_streams_<n>.json`
//! with each stream's Kotlin facts). Run that benchmark first, then:
//! `cargo test --release -p nuvio-core --test bench -- --ignored --nocapture`
//!
//! This is a lower bound for the classifier alone. It is not the cost of the Rust path in the
//! app, which adds JNI encoding, the call and decoding (timed by the Kotlin benchmark).

mod common;

use common::{owned_stream, to_json};
use nuvio_core::release_facts::classify;
use serde_json::Value;
use std::hint::black_box;
use std::time::{Duration, Instant};

const ROUNDS: usize = 5;
const SAMPLES: usize = 61;

#[test]
#[ignore = "benchmark; needs inputs from the Kotlin ProviderProcessingBenchmarkTest"]
fn classify_without_jni() {
    let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../target/bench");
    println!("streams,stage,in,p50_us,p90_us,p99_us,min_us,max_us,round_p50_min_us,round_p50_max_us");
    for size in [50, 150, 400] {
        let path = format!("{dir}/provider_streams_{size}.json");
        let raw = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("{path}: {e}; run the Kotlin benchmark first"));
        let entries: Vec<Value> = serde_json::from_str(&raw).expect("valid benchmark JSON");
        assert!(
            entries.len() >= size * 3 / 4,
            "{path}: only {} streams",
            entries.len()
        );
        let streams: Vec<_> = entries.iter().map(|e| owned_stream(&e["input"])).collect();

        // Same inputs must give the facts Kotlin computed, and those facts must not be trivial.
        let mut recognised = 0;
        for (entry, stream) in entries.iter().zip(&streams) {
            let facts = classify(&stream.input());
            assert_eq!(to_json(&facts), entry["expected"], "input {}", entry["input"]);
            if entry["expected"]["resolution"] != "UNKNOWN" {
                recognised += 1;
            }
        }
        assert!(
            recognised >= streams.len() * 8 / 10,
            "resolutions recognised for only {recognised} of {} streams",
            streams.len()
        );

        let run = || {
            streams
                .iter()
                .map(|s| {
                    let facts = black_box(classify(black_box(&s.input())));
                    facts.languages.len() + facts.release_group.len()
                })
                .sum::<usize>()
        };
        let expected = run();
        assert!(expected > 0);
        let warm_until = Instant::now() + Duration::from_millis(1500);
        while Instant::now() < warm_until {
            assert_eq!(run(), expected);
        }
        let mut samples = Vec::with_capacity(ROUNDS * SAMPLES);
        let mut round_medians = Vec::with_capacity(ROUNDS);
        for _ in 0..ROUNDS {
            let mut round: Vec<f64> = (0..SAMPLES)
                .map(|_| {
                    let start = Instant::now();
                    let result = run();
                    let elapsed = start.elapsed().as_secs_f64() * 1e6;
                    assert_eq!(result, expected);
                    elapsed
                })
                .collect();
            samples.extend_from_slice(&round);
            round.sort_by(f64::total_cmp);
            round_medians.push(round[SAMPLES / 2]);
        }
        samples.sort_by(f64::total_cmp);
        round_medians.sort_by(f64::total_cmp);
        let percentile = |p: usize| samples[((samples.len() - 1) * p + 50) / 100];
        println!(
            "{size},classify_rust_no_jni,{},{:.1},{:.1},{:.1},{:.1},{:.1},{:.1},{:.1}",
            streams.len(),
            percentile(50),
            percentile(90),
            percentile(99),
            samples[0],
            samples[samples.len() - 1],
            round_medians[0],
            round_medians[ROUNDS - 1]
        );
    }
}
