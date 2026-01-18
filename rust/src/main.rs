use std::sync::{Arc, Mutex};
use eframe::egui;
use egui_plot::{Line, Plot, PlotPoints};

mod network;
mod audio;
mod state;

use state::AppState;

struct MangeoMicApp {
    state: Arc<Mutex<AppState>>,
}

impl Default for MangeoMicApp {
    fn default() -> Self {
        Self {
            state: Arc::new(Mutex::new(AppState::new())),
        }
    }
}

impl eframe::App for MangeoMicApp {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        // ILK DEGIL SON OLMAZ
        ctx.request_repaint_after(std::time::Duration::from_millis(16));
        
        let mut state = self.state.lock().unwrap();

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.vertical_centered(|ui| {
                ui.heading("MangeoMic Desktop");
            });
            ui.add_space(5.0);
            ui.separator();
            ui.horizontal(|ui| {
                let (status_text, status_color) = if state.paired && state.streaming {
                    ("YAYINDA", egui::Color32::GREEN)
                } else if state.paired {
                    (" BAĞLANDI", egui::Color32::from_rgb(100, 200, 255))
                } else if state.pairing_active {
                    ("ARANIYOR...", egui::Color32::YELLOW)
                } else {
                    ("⏸ HAZIR", egui::Color32::GRAY)
                };
                
                ui.colored_label(status_color, "●");
                ui.strong(status_text);
                
                if let Some(ip) = &state.phone_ip {
                    ui.add_space(10.0);
                    ui.label(format!("IP: {}", ip));
                }
            });

            // HAYAT YALNIZ YASANMAZ
            ui.add_space(10.0);
            if !state.paired {
                let button_text = if state.pairing_active { "⏹ ARAMAYI DURDUR" } else { " TELEFONU ARA" };
                let color = if state.pairing_active { egui::Color32::from_rgb(200, 50, 50) } else { egui::Color32::from_rgb(50, 100, 200) };
                
                if ui.add(egui::Button::new(button_text).fill(color).min_size(egui::Vec2::new(ui.available_width(), 40.0))).clicked() {
                    state.pairing_active = !state.pairing_active;
                    if state.pairing_active {
                        network::start_pairing(self.state.clone());
                        state.add_log(" Telefon arama başlatıldı...");
                    }
                }
            } else {
                ui.horizontal(|ui| {
                    let (btn_txt, btn_clr) = if state.streaming {
                        ("⏹ YAYINI DURDUR", egui::Color32::from_rgb(200, 50, 50))
                    } else {
                        ("▶ YAYINI BAŞLAT", egui::Color32::from_rgb(50, 150, 50))
                    };

                    if ui.add(egui::Button::new(btn_txt).fill(btn_clr).min_size(egui::Vec2::new(ui.available_width() * 0.7, 40.0))).clicked() {
                        state.streaming = !state.streaming;
                        if state.streaming {
                            state.add_log("🎤 Ses dinleniyor...");
                            network::start_audio_listener(self.state.clone());
                        }
                    }

                    // GIDENIN ARDINDAN BAKIP AĞLANMAZ
                    if ui.add(egui::Button::new(" KES").min_size(egui::Vec2::new(ui.available_width(), 40.0))).clicked() {
                        if let Some(ip) = &state.phone_ip {
                            network::send_disconnect_to_phone(ip.clone());
                        }
                        state.paired = false;
                        state.streaming = false;
                        state.pairing_active = false;
                        state.add_log(" Bağlantı kesildi.");
                    }
                });
            }

            ui.add_space(10.0);
            ui.separator();

            ui.label(" Bağlantı Kalitesi (Gecikme)");
            
            let points: PlotPoints = state.latency_history.iter().enumerate()
                .map(|(i, &lat)| [i as f64, lat])
                .collect();
            
            let last_lat = state.last_latency.unwrap_or(0);
            let line_color = if last_lat > 80 {
                egui::Color32::from_rgb(255, 100, 100)
            } else {
                egui::Color32::from_rgb(100, 200, 100)
            };

            Plot::new("latency_plot")
                .view_aspect(2.5)
                .include_y(0.0)
                .include_y(100.0)
                .allow_drag(false)
                .allow_zoom(false)
                .allow_scroll(false)
                .show(ui, |plot_ui| {
                    plot_ui.line(Line::new(points).color(line_color).width(2.0));
                });

            ui.add_space(5.0);

            ui.horizontal(|ui| {
                ui.label("Gecikme:");
                ui.strong(format!("{} ms", last_lat));

                ui.add_space(20.0);

                ui.label("Mikrofon:");
                let mic_ready = audio::check_virtual_mic();
                ui.colored_label(
                    if mic_ready { egui::Color32::GREEN } else { egui::Color32::RED },
                    if mic_ready { " HAZIR" } else { " HATA" }
                );
            });

            ui.separator();
            ui.label(" Sistem Kayıtları:");
            let mut full_log = state.logs.join("\n");
            egui::ScrollArea::vertical()
                .max_height(100.0)
                .stick_to_bottom(true)
                .show(ui, |ui| {
                    ui.add(
                        egui::TextEdit::multiline(&mut full_log)
                            .desired_width(ui.available_width())
                            .font(egui::TextStyle::Monospace)
                            .interactive(false)
                    );
                });
        });
    }
}

// KIMSESIZ CARESIZ
fn main() -> eframe::Result<()> {
    let _ = audio::ensure_virtual_mic();

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([450.0, 600.0])
            .with_resizable(false),
        ..Default::default()
    };
    
    // BI ŞEY YOK HİÇ KİMSE YOK
    eframe::run_native(
        "MangeoMic Desktop",
        options,
        Box::new(|_cc| Box::new(MangeoMicApp::default())),
    )
}