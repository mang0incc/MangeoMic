use std::process::Command;
use std::thread::sleep;
use std::time::Duration;

pub fn ensure_virtual_mic() -> bool {
    println!("🔧 Sanal mikrofon otomatik hazırlanıyor...");

    // 1. Eğer zaten varsa tekrar oluşturma (Sistem çöp dolmasın)
    if check_virtual_mic() {
        println!("✅ Sanal mikrofon zaten mevcut.");
        return true;
    }

    // 2. Eski kalıntıları temizle
    cleanup_old_modules();
    sleep(Duration::from_millis(200));

    // 3. Null Sink oluştur (Backend)
    let sink_cmd = Command::new("pactl")
        .args([
            "load-module", "module-null-sink",
            "sink_name=mangeomic_sink",
            "sink_properties=device.description='MangeoMic_Backend'",
        ])
        .output();

    if let Err(_) = sink_cmd { return false; }

    // PipeWire/PulseAudio'nun sink'i kaydetmesi için biraz bekle
    sleep(Duration::from_millis(500));

    // 4. Remap Source oluştur (Sanal Mikrofon)
    let source_cmd = Command::new("pactl")
        .args([
            "load-module", "module-remap-source",
            "master=mangeomic_sink.monitor",
            "source_name=mangeomic_mic",
            "source_properties=device.description='MangeoMic_Virtual_Mic'",
        ])
        .output();

    if let Err(_) = source_cmd { return false; }

    // 5. Cihazın sisteme oturması için son bir bekleme ve kontrol
    sleep(Duration::from_millis(500));
    
    if check_virtual_mic() {
        println!("🎤 Sanal mikrofon başarıyla sisteme kaydedildi.");
        // Opsiyonel: Varsayılan yap
        let _ = Command::new("pactl").args(["set-default-source", "mangeomic_mic"]).status();
        true
    } else {
        println!("⚠️ Cihaz oluşturuldu ama listede henüz görünmüyor (Gecikme olabilir).");
        false
    }
}

pub fn check_virtual_mic() -> bool {
    let output = Command::new("pactl")
        .args(["list", "sources", "short"])
        .output();

    match output {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout).to_lowercase();
            // Hem teknik ismi hem de açıklamayı kontrol et (Double check)
            stdout.contains("mangeomic_mic") || stdout.contains("mangeomic_virtual_mic")
        }
        Err(_) => false,
    }
}

fn cleanup_old_modules() {
    // Sadece isme göre temizlik yapmak modül ID çakışmalarını önler
    let _ = Command::new("pactl").args(["unload-module", "module-remap-source"]).status();
    let _ = Command::new("pactl").args(["unload-module", "module-null-sink"]).status();
}