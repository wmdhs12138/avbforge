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
