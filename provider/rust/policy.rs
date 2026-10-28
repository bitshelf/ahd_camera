// SPDX-License-Identifier: Apache-2.0
//! Platform-independent resource policy. No Binder, IO, graphics or unsafe code.
use std::collections::BTreeSet;

#[derive(Debug, PartialEq, Eq)]
pub enum QueryError {
    DuplicateId,
    InvalidSize,
}

pub fn unique_ids<'a>(ids: impl Iterator<Item = &'a str>) -> Result<(), QueryError> {
    let mut seen = BTreeSet::new();
    for id in ids {
        if !seen.insert(id) {
            return Err(QueryError::DuplicateId);
        }
    }
    Ok(())
}

pub fn stream_area(width: i32, height: i32) -> Result<u64, QueryError> {
    if !(1..=32768).contains(&width) || !(1..=32768).contains(&height) {
        return Err(QueryError::InvalidSize);
    }
    Ok(width as u64 * height as u64)
}

pub fn add_load(total: u64, native_pixels: u64, output_pixels: u64, fps: u32) -> Option<u64> {
    if !(1..=1000).contains(&fps) || native_pixels == 0 {
        return None;
    }
    total.checked_add(native_pixels.max(output_pixels).checked_mul(fps as u64)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_duplicate_camera_to_prevent_undercounting() {
        assert_eq!(
            unique_ids(["ahd-0", "ahd-0"].into_iter()),
            Err(QueryError::DuplicateId)
        );
        assert!(unique_ids(["ahd-0", "ahd-1"].into_iter()).is_ok());
    }
    #[test]
    fn downscaled_preview_still_charges_full_native_capture() {
        let mut load = 0;
        for _ in 0..3 {
            load = add_load(load, 1920 * 1080, 640 * 480, 25).unwrap();
        }
        assert_eq!(load, 155_520_000);
        assert!(add_load(load, 1920 * 1080, 640 * 480, 25).unwrap() > 155_520_000);
    }
    #[test]
    fn malformed_or_overflowing_load_fails_closed() {
        assert!(add_load(u64::MAX, 1920 * 1080, 0, 25).is_none());
        assert!(add_load(0, u64::MAX, 0, 25).is_none());
        assert!(add_load(0, 1, 1, 0).is_none());
        assert!(add_load(0, 0, 1, 25).is_none());
        for (w, h) in [(0, 1080), (-1, 1), (1, 32769), (i32::MAX, i32::MAX)] {
            assert_eq!(stream_area(w, h), Err(QueryError::InvalidSize));
        }
        assert_eq!(stream_area(1920, 1080), Ok(2_073_600));
    }
}
