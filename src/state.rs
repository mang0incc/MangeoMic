use std::time::Instant;

pub struct AppState {
    pub pairing_active: bool,
    pub paired: bool,
    pub streaming: bool,
    pub phone_ip: Option<String>,
    pub logs: Vec<String>,
    pub last_latency: Option<u32>,
    pub last_heartbeat: Instant,
    pub packet_count: u64,          
    pub latency_history: Vec<f64>,   
}

impl AppState {
    pub fn new() -> Self {
        Self {
            pairing_active: true,
            paired: false,
            streaming: false,
            phone_ip: None,
            logs: vec!["🚀 Sistem hazır.".to_string()],
            last_latency: None,
            last_heartbeat: Instant::now(),
            packet_count: 0,
            latency_history: vec![0.0; 50],
        }
    }

    pub fn add_log(&mut self, msg: &str) {
        let timestamp = chrono::Local::now().format("%H:%M:%S").to_string();
        self.logs.push(format!("[{}] {}", timestamp, msg));
        if self.logs.len() > 50 { self.logs.remove(0); }
    }

    pub fn push_latency(&mut self, lat: f64) {
        self.latency_history.push(lat);
        if self.latency_history.len() > 50 {
            self.latency_history.remove(0);
        }
    }
}