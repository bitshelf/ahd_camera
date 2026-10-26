//! A one-frame mailbox. Replaced leases are recycled outside the lock.
use std::sync::{Condvar, Mutex};
use std::time::Duration;

struct State<T> {
    frame: Option<T>,
    closed: bool,
    replaced: u64,
}

/// A bounded mailbox that owns at most one pending frame lease.
pub struct LatestFrame<T> {
    state: Mutex<State<T>>,
    ready: Condvar,
}

impl<T> Default for LatestFrame<T> {
    fn default() -> Self {
        Self {
            state: Mutex::new(State {
                frame: None,
                closed: false,
                replaced: 0,
            }),
            ready: Condvar::new(),
        }
    }
}

impl<T> LatestFrame<T> {
    /// Publish the newest lease, recycling any replaced lease outside the mutex.
    pub fn publish(&self, frame: T) -> Result<(), T> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closed {
            return Err(frame);
        }
        let previous = state.frame.replace(frame);
        if previous.is_some() {
            state.replaced = state.replaced.saturating_add(1);
        }
        drop(state);
        self.ready.notify_one();
        drop(previous);
        Ok(())
    }

    /// Wait for a lease until timeout or close; removes it from the mailbox.
    pub fn take(&self, timeout: Duration) -> Option<T> {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let (mut state, _) = self
            .ready
            .wait_timeout_while(state, timeout, |s| s.frame.is_none() && !s.closed)
            .unwrap_or_else(|e| e.into_inner());
        state.frame.take()
    }

    /// Reject future publishers, release pending data, and wake all consumers.
    pub fn close(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.closed = true;
        let frame = state.frame.take();
        drop(state);
        self.ready.notify_all();
        drop(frame);
    }

    /// Number of pending frames displaced by a newer publication.
    pub fn replaced(&self) -> u64 {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .replaced
    }
    /// Whether close has been requested.
    pub fn is_closed(&self) -> bool {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).closed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    struct Lease(Arc<AtomicUsize>);
    impl Drop for Lease {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }
    #[test]
    fn newest_frame_wins_and_old_lease_is_recycled() {
        let mailbox = LatestFrame::default();
        mailbox.publish(1).unwrap();
        mailbox.publish(2).unwrap();
        assert_eq!(mailbox.take(Duration::ZERO), Some(2));
        assert_eq!(mailbox.replaced(), 1);
    }
    #[test]
    fn close_releases_pending_lease_and_rejects_new_frames() {
        let count = Arc::new(AtomicUsize::new(0));
        let mailbox = LatestFrame::default();
        assert!(mailbox.publish(Lease(count.clone())).is_ok());
        mailbox.close();
        assert_eq!(count.load(Ordering::SeqCst), 1);
        let rejected = mailbox.publish(Lease(count.clone()));
        assert!(rejected.is_err());
        drop(rejected);
        assert_eq!(count.load(Ordering::SeqCst), 2);
        assert!(mailbox.take(Duration::from_secs(1)).is_none());
    }
    #[test]
    fn close_wakes_waiting_consumer() {
        let mailbox = Arc::new(LatestFrame::<u8>::default());
        let other = mailbox.clone();
        let consumer = std::thread::spawn(move || other.take(Duration::from_secs(10)));
        mailbox.close();
        assert_eq!(consumer.join().unwrap(), None);
    }
}
