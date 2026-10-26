//! Input timing and delivery pacing are independent of vendor capture controls.

/// Estimate input cadence from source timestamps and sequence counters.
#[derive(Default)]
pub struct InputRate {
    origin: Option<(u64, u32)>,
}
impl InputRate {
    /// Use source sequence numbers so skipped dequeue buffers do not lower the input estimate.
    pub fn observe(&mut self, timestamp_ns: u64, sequence: u32) -> Option<f32> {
        let Some((start, first)) = self.origin else {
            self.origin = Some((timestamp_ns, sequence));
            return None;
        };
        if timestamp_ns <= start {
            self.origin = Some((timestamp_ns, sequence));
            return None;
        }
        let elapsed = timestamp_ns - start;
        if elapsed < 1_000_000_000 {
            return None;
        }
        self.origin = Some((timestamp_ns, sequence));
        let frames = sequence.wrapping_sub(first);
        if frames == 0 || frames > 1_000_000 {
            return None;
        }
        Some(frames as f32 * 1_000_000_000.0 / elapsed as f32)
    }
}

/// Preserve phase without accumulating integer-nanosecond rounding error or queuing frames.
pub struct FramePacer {
    fps: u32,
    deadline: Option<u128>,
    previous: Option<u64>,
}
impl FramePacer {
    /// Create a bounded delivery schedule; zero is defensively treated as one.
    pub fn new(fps: u32) -> Self {
        Self {
            fps: fps.max(1),
            deadline: None,
            previous: None,
        }
    }
    /// Return whether the newest frame is due, without buffering or waiting.
    pub fn accept(&mut self, timestamp_ns: u64) -> bool {
        if self.previous.is_some_and(|last| timestamp_ns <= last) {
            self.deadline = None;
        }
        self.previous = Some(timestamp_ns);
        let tick = timestamp_ns as u128 * self.fps as u128;
        let Some(deadline) = self.deadline else {
            self.deadline = Some(tick + 1_000_000_000);
            return true;
        };
        // Tolerate source IRQ jitter without unnecessarily skipping matching-rate frames.
        let tolerance = (self.fps as u128 * 2_000_000).min(50_000_000);
        if tick.saturating_add(tolerance) < deadline {
            return false;
        }
        self.deadline =
            Some(deadline + (tick.saturating_sub(deadline) / 1_000_000_000 + 1) * 1_000_000_000);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn input_rate_counts_sequence_gaps_and_wrap() {
        let mut rate = InputRate::default();
        assert_eq!(rate.observe(1, u32::MAX - 9), None);
        assert_eq!(rate.observe(1_000_000_001, 15), Some(25.0));
    }
    #[test]
    fn backward_timestamp_restarts_input_measurement() {
        let mut rate = InputRate::default();
        rate.observe(3_000_000_000, 3);
        assert_eq!(rate.observe(1_000_000_000, 0), None);
        assert_eq!(rate.observe(2_000_000_000, 25), Some(25.0));
    }
    #[test]
    fn delivery_rate_matches_choice_when_driver_cannot_change_source() {
        for requested in [1, 10, 24, 25, 30] {
            let mut pace = FramePacer::new(requested);
            let accepted = (0..250).filter(|i| pace.accept(i * 40_000_000)).count();
            assert_eq!(accepted, requested.min(25) as usize * 10);
        }
    }
    #[test]
    fn matching_rate_tolerates_source_timestamp_jitter() {
        let mut pace = FramePacer::new(25);
        for i in 0..250_u64 {
            let jitter = if i % 2 == 0 { 0 } else { 500_000 };
            assert!(pace.accept(i * 40_000_000 + jitter));
        }
    }
    #[test]
    fn long_pause_does_not_create_a_delivery_burst() {
        let mut pace = FramePacer::new(10);
        assert!(pace.accept(0));
        assert!(pace.accept(5_000_000_000));
        assert!(!pace.accept(5_001_000_000));
    }
}
