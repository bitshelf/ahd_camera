//! Explicit-node diagnostic. Running as root is NOT application permission acceptance.
use ahd_v4l2::{probe_capabilities, probe_sizes, Capture};
use std::time::{Duration, Instant};

fn main() {
    let mut args = std::env::args().skip(1);
    let first = args.next().unwrap_or_default();
    let (frames, nodes) = if first == "--frames" {
        let count: usize = args
            .next()
            .and_then(|s| s.parse().ok())
            .filter(|n| *n > 0 && *n <= 10000)
            .expect("--frames requires 1..10000");
        (count, args.collect::<Vec<_>>())
    } else {
        (
            0,
            std::iter::once(first)
                .chain(args)
                .filter(|s| !s.is_empty())
                .collect(),
        )
    };
    if nodes.is_empty() {
        eprintln!("usage: ahd-v4l2-probe [--frames N] /dev/videoN ...");
        std::process::exit(2);
    }
    let success = std::thread::scope(|scope| {
        let workers = nodes.iter().map(|node| scope.spawn(move || -> Result<(), String> {
            let caps = probe_capabilities(node).map_err(|e| format!("{node}: {e}"))?;
            println!("CAPABILITIES {node} {caps}");
            if frames == 0 { return Ok(()); }
            let (width, height) = probe_sizes(node).map_err(|e| e.to_string())?.into_iter()
                .max_by_key(|(w,h)| u64::from(*w)*u64::from(*h)).ok_or("no supported sizes")?;
            let prefix = format!("{width}x{height}@");
            let fps = caps.split(';').find_map(|entry| entry.strip_prefix(&prefix))
                .into_iter().flat_map(|rates| rates.split(',')).filter_map(|rate| rate.split('-').nth(1)?.parse::<u32>().ok())
                .max().ok_or("no integral driver-reported frame rates")?;
            let mut capture = Capture::open(node, width, height, fps).map_err(|e| e.to_string())?;
            let start = Instant::now();
            let mut last_frame = start;
            let mut count = 0;
            while count < frames {
                if let Some(frame) = capture.latest(100).map_err(|e| e.to_string())? {
                    drop(frame);
                    count += 1;
                    last_frame = Instant::now();
                } else if last_frame.elapsed() > Duration::from_secs(3) { return Err(format!("{node}: no frames for 3 seconds")); }
            }
            println!("CAPTURE {node} frames={count} elapsed_s={:.3} fps={:.3} skipped={} layout={:?}", start.elapsed().as_secs_f64(), count as f64/start.elapsed().as_secs_f64(),capture.dropped,capture.layout);
            Ok(())
        })).collect::<Vec<_>>();
        workers
            .into_iter()
            .map(|worker| match worker.join() {
                Ok(Ok(())) => true,
                Ok(Err(error)) => {
                    eprintln!("ERROR {error}");
                    false
                }
                Err(_) => {
                    eprintln!("ERROR capture worker panicked");
                    false
                }
            })
            .fold(true, |success, value| success & value)
    });
    if !success {
        std::process::exit(1);
    }
}
