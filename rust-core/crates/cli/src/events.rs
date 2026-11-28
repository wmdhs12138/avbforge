use serde::Serialize;

#[derive(Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum CoreEvent<'a, T: Serialize> {
    Log { level: &'a str, message: &'a str },
    Progress { stage: &'a str, current: u64, total: u64 },
    Result { data: T },
    Error { message: String },
}

pub fn emit_log(level: &str, message: &str) {
    let ev: CoreEvent<()> = CoreEvent::Log { level, message };
    println!("{}", serde_json::to_string(&ev).unwrap());
}

pub fn emit_progress(stage: &str, current: u64, total: u64) {
    let ev: CoreEvent<()> = CoreEvent::Progress {
        stage,
        current,
        total,
    };
    println!("{}", serde_json::to_string(&ev).unwrap());
}

pub fn emit_result<T: Serialize>(data: T) {
    let ev = CoreEvent::Result { data };
    println!("{}", serde_json::to_string(&ev).unwrap());
}

pub fn emit_error(message: String) {
    let ev: CoreEvent<()> = CoreEvent::Error { message };
    eprintln!("{}", serde_json::to_string(&ev).unwrap());
}

pub mod whitebox {
    use anyhow::Result;
    use serde::Serialize;
    use std::collections::BTreeMap;
    use std::io::{self, Write};
    use std::time::Instant;

    #[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
    #[serde(rename_all = "snake_case")]
    pub enum GateKind {
        OfflineVerify,
        BlockFlash,
        ReadbackVerify,
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
    #[serde(rename_all = "snake_case")]
    pub enum StepLogLevel {
        Info,
        Kv,
        Ok,
        Warn,
        Error,
    }

    #[derive(Debug, Clone, Serialize)]
    pub struct PlannedStep {
        pub step_id: String,
        pub title: String,
        pub cmd: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        pub target: Option<String>,
    }

    impl PlannedStep {
        pub fn new(
            step_id: impl Into<String>,
            title: impl Into<String>,
            cmd: impl Into<String>,
        ) -> Self {
            Self {
                step_id: step_id.into(),
                title: title.into(),
                cmd: cmd.into(),
                target: None,
            }
        }
    }

    #[derive(Debug, Serialize)]
    #[serde(tag = "event", rename_all = "snake_case")]
    pub enum WhiteBoxEvent {
        Plan {
            steps: Vec<PlannedStep>,
        },
        StepStart {
            step_id: String,
            title: String,
            cmd: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            gate: Option<GateKind>,
        },
        StepLog {
            step_id: String,
            level: StepLogLevel,
            message: String,
            #[serde(skip_serializing_if = "BTreeMap::is_empty")]
            kv: BTreeMap<String, String>,
        },
        StepEnd {
            step_id: String,
            ok: bool,
            exit_code: i32,
            duration_ms: u64,
            #[serde(skip_serializing_if = "Option::is_none")]
            gate: Option<GateKind>,
            #[serde(skip_serializing_if = "Option::is_none")]
            summary: Option<String>,
        },
    }

    pub fn emit_whitebox(ev: &WhiteBoxEvent) {
        if let Ok(line) = serde_json::to_string(ev) {
            let mut out = io::stdout().lock();
            let _ = writeln!(out, "{line}");
            let _ = out.flush();
        }
    }

    pub fn emit_plan(steps: Vec<PlannedStep>) {
        emit_whitebox(&WhiteBoxEvent::Plan { steps });
    }

    /// Execute a closure inside a white-box `StepGuard`, automatically emitting `step_start` and
    /// `step_end` (with exit code 0 on `Ok` or exit code 1 + formatted error chain on `Err`).
    pub fn run_step<T, F>(
        step_id: impl Into<String>,
        title: impl Into<String>,
        cmd: impl Into<String>,
        gate: Option<GateKind>,
        ok_summary: &str,
        f: F,
    ) -> Result<T>
    where
        F: FnOnce(&StepGuard) -> Result<T>,
    {
        let guard = StepGuard::begin(step_id, title, cmd, gate);
        match f(&guard) {
            Ok(val) => {
                guard.finish_ok(Some(ok_summary));
                Ok(val)
            }
            Err(err) => {
                guard.finish_err(1, format!("{err:#}"));
                Err(err)
            }
        }
    }

    pub struct StepGuard {
        step_id: String,
        gate: Option<GateKind>,
        started_at: Instant,
        finished: bool,
    }

    impl StepGuard {
        pub fn begin(
            step_id: impl Into<String>,
            title: impl Into<String>,
            cmd: impl Into<String>,
            gate: Option<GateKind>,
        ) -> Self {
            let step_id = step_id.into();
            emit_whitebox(&WhiteBoxEvent::StepStart {
                step_id: step_id.clone(),
                title: title.into(),
                cmd: cmd.into(),
                gate,
            });
            Self {
                step_id,
                gate,
                started_at: Instant::now(),
                finished: false,
            }
        }

        pub fn info(&self, message: impl Into<String>) {
            self.log(StepLogLevel::Info, message, BTreeMap::new());
        }

        pub fn ok_line(&self, message: impl Into<String>) {
            self.log(StepLogLevel::Ok, message, BTreeMap::new());
        }

        pub fn warn(&self, message: impl Into<String>) {
            self.log(StepLogLevel::Warn, message, BTreeMap::new());
        }

        pub fn kv(&self, message: impl Into<String>, pairs: &[(&str, &str)]) {
            let kv = pairs
                .iter()
                .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
                .collect();
            self.log(StepLogLevel::Kv, message, kv);
        }

        pub fn log(&self, level: StepLogLevel, message: impl Into<String>, kv: BTreeMap<String, String>) {
            emit_whitebox(&WhiteBoxEvent::StepLog {
                step_id: self.step_id.clone(),
                level,
                message: message.into(),
                kv,
            });
        }

        pub fn finish_ok(mut self, summary: Option<&str>) {
            self.finished = true;
            emit_whitebox(&WhiteBoxEvent::StepEnd {
                step_id: self.step_id.clone(),
                ok: true,
                exit_code: 0,
                duration_ms: self.started_at.elapsed().as_millis() as u64,
                gate: self.gate,
                summary: summary.map(str::to_string),
            });
        }

        pub fn finish_err(mut self, exit_code: i32, summary: impl Into<String>) {
            self.finished = true;
            let msg = summary.into();
            self.log(StepLogLevel::Error, msg.clone(), BTreeMap::new());
            emit_whitebox(&WhiteBoxEvent::StepEnd {
                step_id: self.step_id.clone(),
                ok: false,
                exit_code,
                duration_ms: self.started_at.elapsed().as_millis() as u64,
                gate: self.gate,
                summary: Some(msg),
            });
        }
    }

    impl Drop for StepGuard {
        fn drop(&mut self) {
            if !self.finished {
                emit_whitebox(&WhiteBoxEvent::StepEnd {
                    step_id: self.step_id.clone(),
                    ok: false,
                    exit_code: -1,
                    duration_ms: self.started_at.elapsed().as_millis() as u64,
                    gate: self.gate,
                    summary: Some("step aborted unexpectedly".to_string()),
                });
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn serialize_step_start_uses_event_tag() {
            let ev = WhiteBoxEvent::StepStart {
                step_id: "verify".to_string(),
                title: "Readback verify".to_string(),
                cmd: "avbforge-core verify-readback".to_string(),
                gate: Some(GateKind::ReadbackVerify),
            };
            let json = serde_json::to_string(&ev).unwrap();
            assert!(json.contains("\"event\":\"step_start\""));
            assert!(json.contains("\"gate\":\"readback_verify\""));
        }

        #[test]
        fn run_step_propagates_ok_and_err() {
            let ok_val: i32 = run_step(
                "unit_ok",
                "Unit ok",
                "avbforge-core test-ok",
                Some(GateKind::OfflineVerify),
                "PASS",
                |step| {
                    step.kv("check", &[("result", "ok")]);
                    step.ok_line("passed");
                    Ok(42)
                },
            )
            .unwrap();
            assert_eq!(ok_val, 42);

            let err: Result<()> = run_step(
                "unit_err",
                "Unit err",
                "avbforge-core test-err",
                Some(GateKind::ReadbackVerify),
                "PASS",
                |_step| anyhow::bail!("simulated mismatch"),
            );
            assert!(err.is_err());
        }
    }
}
