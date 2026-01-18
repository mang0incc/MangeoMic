use std::net::UdpSocket;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::process::{Command, Stdio};
use std::io::Write;
use std::thread;
use crate::state::AppState;

const DISCOVER_MSG: &[u8] = b"MANGEO_DISCOVER";
const HI_MSG: &[u8] = b"MANGEO_HI";
const OK_MSG: &[u8] = b"MANGEO_OK";
const DISCONNECT_MSG: &[u8] = b"MANGEO_BYE";
const HEARTBEAT_MSG: &[u8] = b"MANGOVAR";
const KEEP_ALIVE_MSG: &[u8] = b"MANGOHI"; 

pub fn start_pairing(state: Arc<Mutex<AppState>>) {
    let state_clone = state.clone();
    thread::spawn(move || {
        let socket = UdpSocket::bind("0.0.0.0:50004").unwrap();
        socket.set_broadcast(true).ok();
        socket.set_read_timeout(Some(Duration::from_millis(1000))).ok();
        
        loop {
            if !state_clone.lock().unwrap().pairing_active { break; }
            let _ = socket.send_to(DISCOVER_MSG, "255.255.255.255:50004");
            
            let mut buf = [0u8; 128];
            if let Ok((len, src)) = socket.recv_from(&mut buf) {
                if &buf[..len] == HI_MSG {
                    let _ = socket.send_to(OK_MSG, src);
                    let mut st = state_clone.lock().unwrap();
                    st.paired = true;
                    st.phone_ip = Some(src.ip().to_string());
                    st.add_log(&format!(" {} ile eşleşildi", src.ip()));
                    break;
                }
            }
            thread::sleep(Duration::from_millis(1000));
        }
    });
}

// BİR ŞEY FARK ETMEZ
pub fn send_disconnect_to_phone(phone_ip: String) {
    let socket = UdpSocket::bind("0.0.0.0:0").ok();
    let _ = socket.map(|s| s.send_to(DISCONNECT_MSG, format!("{}:50006", phone_ip)));
}

// ELİMDEN HİÇBİR ŞEY GELMEZ HİÇBİR ÇAREM YOK
pub fn start_audio_listener(state: Arc<Mutex<AppState>>) {
    let state_clone = state.clone();
    thread::spawn(move || {
        let _ = Command::new("pkill").args(["-f", "pacat.*mangeomic"]).status();
        let socket = UdpSocket::bind("0.0.0.0:50006").expect("Port 50006 meşgul");
        socket.set_read_timeout(Some(Duration::from_millis(100))).ok();

        let phone_addr = {
            let st = state_clone.lock().unwrap();
            format!("{}:50006", st.phone_ip.clone().unwrap_or_default())
        };

        let mut child = Command::new("pacat")
            .args(["--playback", "--format=s16le", "--rate=44100", "--channels=1", "--device=mangeomic_sink", "--latency-msec=20"])
            .stdin(Stdio::piped()).spawn().expect("Pacat fail");

        let mut stdin = child.stdin.take().unwrap();
        let mut buffer = [0u8; 4096];
        let mut last_packet_time = Instant::now();
        let mut last_hi_sent = Instant::now();

        {
            let mut st = state_clone.lock().unwrap();
            st.last_heartbeat = Instant::now();
        }

        // KARANLIK BU SOKAKLARDA SESİMİ DUYAN YOK
        loop {
            if !state_clone.lock().unwrap().streaming { break; }

            if last_hi_sent.elapsed() > Duration::from_millis(500) {
                let _ = socket.send_to(KEEP_ALIVE_MSG, &phone_addr);
                last_hi_sent = Instant::now();
            }

            match socket.recv(&mut buffer) {
                Ok(len) if len > 0 => {
                    let data = &buffer[..len];
                    let mut st = state_clone.lock().unwrap();
                    st.last_heartbeat = Instant::now();

                    if data == DISCONNECT_MSG {
                        st.paired = false;
                        st.streaming = false;
                        st.add_log(" Telefon bağlantıyı kesti.");
                        break;
                    } else if data == HEARTBEAT_MSG {
                        continue; 
                    } else {
                        let now = Instant::now();
                        let lat = now.duration_since(last_packet_time).as_millis() as f64;
                        last_packet_time = now;
                        st.push_latency(lat);
                        st.last_latency = Some(lat as u32);
                        st.packet_count += 1;
                        let _ = stdin.write_all(data);
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    let st = state_clone.lock().unwrap();
                    if st.last_heartbeat.elapsed() > Duration::from_secs(5) {
                        drop(st);
                        let mut st_write = state_clone.lock().unwrap();
                        st_write.paired = false;
                        st_write.streaming = false;
                        st_write.add_log(" İletişim koptu: Paket gelmiyor.");
                        break;
                    }
                }
                _ => {}
            }
        }
        // ELİMDEN HİÇBİR ŞEY GELMEZ HİÇBİR ÇAREM YOK
        let _ = child.kill();
    });
}