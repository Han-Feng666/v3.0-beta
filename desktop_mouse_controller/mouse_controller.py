from __future__ import annotations

import threading
import time
import tkinter as tk
import sys
import json
import os
from queue import SimpleQueue
from dataclasses import dataclass
from math import cos, radians, sin
from pathlib import Path
from tkinter import messagebox, ttk

try:
    from pynput import keyboard, mouse
except ImportError as exc:
    root = tk.Tk()
    root.withdraw()
    messagebox.showerror(
        "缺少依赖",
        "程序需要先安装 pynput。\n\n请在当前目录执行：\npip install -r requirements.txt",
    )
    raise SystemExit(1) from exc


MOVE_PER_TICK = 1.0
DIRECTION_ANGLES = {
    "向右": 0.0,
    "右下": 45.0,
    "向下": 90.0,
    "左下": 135.0,
    "向左": 180.0,
    "左上": 225.0,
    "向上": 270.0,
    "右上": 315.0,
    "停止": -1.0,
    "自定义角度": 90.0,
}
INTERVAL_PRESETS = {
    "0.5 秒": 500.0,
    "0.2 秒": 200.0,
    "0.1 秒": 100.0,
    "0.05 秒": 50.0,
    "30 ms": 30.0,
    "10 ms": 10.0,
    "自定义": None,
}
CONFIG_FILE_NAME = "settings.json"
HOTKEY_ALIASES = {
    "key.space": "space",
    "空格": "space",
    "空格键": "space",
    "key.enter": "enter",
    "回车": "enter",
    "回车键": "enter",
    "key.esc": "esc",
    "escape": "esc",
    "退出键": "esc",
    "key.tab": "tab",
    "制表键": "tab",
    "key.backspace": "backspace",
    "退格": "backspace",
    "key.delete": "delete",
    "删除": "delete",
    "key.up": "up",
    "上": "up",
    "向上": "up",
    "key.down": "down",
    "下": "down",
    "向下": "down",
    "key.left": "left",
    "左": "left",
    "向左": "left",
    "key.right": "right",
    "右": "right",
    "向右": "right",
    "数字0": "0",
    "数字1": "1",
    "数字2": "2",
    "数字3": "3",
    "数字4": "4",
    "数字5": "5",
    "数字6": "6",
    "数字7": "7",
    "数字8": "8",
    "数字9": "9",
    "小键盘0": "0",
    "小键盘1": "1",
    "小键盘2": "2",
    "小键盘3": "3",
    "小键盘4": "4",
    "小键盘5": "5",
    "小键盘6": "6",
    "小键盘7": "7",
    "小键盘8": "8",
    "小键盘9": "9",
    "numpad0": "0",
    "numpad1": "1",
    "numpad2": "2",
    "numpad3": "3",
    "numpad4": "4",
    "numpad5": "5",
    "numpad6": "6",
    "numpad7": "7",
    "numpad8": "8",
    "numpad9": "9",
}


@dataclass
class MoveSettings:
    hotkey: str = "a"
    dx: float = 0.0
    dy: float = 1.0
    interval_ms: float = 30.0
    angle_degrees: float = 90.0
    frequency_stages: tuple[tuple[float, float], ...] = ()


@dataclass
class ProfileConfig:
    name: str
    switch_hotkey: str
    direction: str
    angle_degrees: str
    interval_preset: str
    interval_ms: str
    enable_frequency_stages: bool
    stage_time: str
    stage_interval: str


class MouseControllerApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("鼠标滑动控制器")
        self.root.resizable(False, False)
        self._apply_window_icon()

        self.config_path = self._config_path()
        self.saved_config = self._load_config()
        self.settings = MoveSettings()
        self.lock = threading.Lock()
        self.enabled = False
        self.left_pressed = False
        self.running = True
        self.hotkey_down = False
        self.capturing_hotkey = False
        self.ui_events: SimpleQueue[tuple[str, str | None]] = SimpleQueue()
        self.move_x_remainder = 0.0
        self.move_y_remainder = 0.0
        self.left_press_started_at: float | None = None
        self.dark_mode = bool(self.saved_config.get("dark_mode", False))
        self.profiles = self._load_profiles(self.saved_config.get("profiles"))
        self.active_profile_name = str(self.saved_config.get("active_profile_name", ""))
        self.profile_hotkeys_down: set[str] = set()

        self.mouse_controller = mouse.Controller()
        self.keyboard_listener: keyboard.Listener | None = None
        self.mouse_listener: mouse.Listener | None = None

        self.hotkey_var = tk.StringVar(value=self._normalize_hotkey(str(self.saved_config.get("hotkey", self.settings.hotkey))))
        self.dx_var = tk.StringVar(value=f"{self.settings.dx:.2f}")
        self.dy_var = tk.StringVar(value=f"{self.settings.dy:.2f}")
        self.direction_var = tk.StringVar(value=str(self.saved_config.get("direction", "向下")))
        self.angle_var = tk.StringVar(value=str(self.saved_config.get("angle_degrees", f"{self.settings.angle_degrees:g}")))
        self.interval_preset_var = tk.StringVar(value=str(self.saved_config.get("interval_preset", "30 ms")))
        self.interval_var = tk.StringVar(value=str(self.saved_config.get("interval_ms", f"{self.settings.interval_ms:g}")))
        self.enable_frequency_stages_var = tk.BooleanVar(value=bool(self.saved_config.get("enable_frequency_stages", False)))
        self.stage_time_var = tk.StringVar(value=str(self.saved_config.get("stage_time", "1.5")))
        self.stage_interval_var = tk.StringVar(value=str(self.saved_config.get("stage_interval", "50")))
        self.direction_status_var = tk.StringVar(value="方向：向下")
        self.status_var = tk.StringVar(value="状态：关闭")
        self.left_status_var = tk.StringVar(value="左键：未按下")
        self.hotkey_hint_var = tk.StringVar(value="点击“录制热键”后，按键盘上的任意键。")
        self.active_profile_var = tk.StringVar(value=self._active_profile_text())
        self.toggle_button_text = tk.StringVar(value="开启")
        self.style = ttk.Style()
        self.style.theme_use("clam")

        self._build_ui()
        self.apply_settings(show_message=False)
        self._apply_theme()
        self._start_listeners()
        self.worker = threading.Thread(target=self._move_loop, daemon=True)
        self.worker.start()

        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(50, self._process_ui_events)
        self.root.after(200, self._refresh_status)

    def _build_ui(self) -> None:
        frame = ttk.Frame(self.root, padding=8, style="App.TFrame")
        self.main_frame = frame
        frame.grid(row=0, column=0, sticky="nsew")
        frame.columnconfigure(0, weight=1)
        frame.columnconfigure(1, weight=1)

        ttk.Label(frame, text="鼠标滑动控制器", style="Title.TLabel").grid(row=0, column=0, sticky="w")

        top_actions = ttk.Frame(frame, style="App.TFrame")
        top_actions.grid(row=0, column=1, sticky="e")
        ttk.Button(top_actions, textvariable=self.toggle_button_text, command=self.toggle_enabled, style="Primary.TButton").grid(row=0, column=0)
        self.theme_canvas = tk.Canvas(top_actions, width=34, height=30, highlightthickness=0, cursor="hand2")
        self.theme_canvas.grid(row=0, column=1, padx=(6, 0))
        self.theme_canvas.bind("<Button-1>", lambda _event: self.toggle_theme())
        ttk.Button(top_actions, text="配置", command=self.open_profiles_window).grid(row=0, column=2, padx=(6, 0))
        ttk.Button(top_actions, text="说明", command=self.show_usage).grid(row=0, column=3, padx=(6, 0))

        settings_frame = ttk.LabelFrame(frame, text="基础设置", padding=6)
        settings_frame.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        settings_frame.columnconfigure(1, weight=1)

        ttk.Label(settings_frame, text="滑动间隔", style="Card.TLabel").grid(row=0, column=0, sticky="w")
        interval_box = ttk.Combobox(
            settings_frame,
            textvariable=self.interval_preset_var,
            values=list(INTERVAL_PRESETS.keys()),
            width=12,
            state="readonly",
        )
        interval_box.grid(row=0, column=1, sticky="ew", padx=(12, 0))
        interval_box.bind("<<ComboboxSelected>>", lambda _event: self._select_interval_preset())
        ttk.Entry(settings_frame, textvariable=self.interval_var, width=10).grid(row=0, column=2, sticky="ew", padx=(8, 0))
        ttk.Label(settings_frame, text="ms", style="Card.TLabel").grid(row=0, column=3, sticky="w", padx=(6, 0))
        ttk.Label(settings_frame, text="默认 30ms，数值越小越快", style="CardHint.TLabel").grid(row=1, column=1, columnspan=3, sticky="w", padx=(12, 0), pady=(3, 0))
        ttk.Checkbutton(
            settings_frame,
            text="启用分阶频率",
            variable=self.enable_frequency_stages_var,
            command=self._toggle_frequency_stages,
        ).grid(row=2, column=0, columnspan=4, sticky="w", pady=(6, 0))

        self.frequency_stage_frame = ttk.Frame(settings_frame)
        self.frequency_stage_time_label = ttk.Label(self.frequency_stage_frame, text="时间", style="Card.TLabel")
        self.frequency_stage_time_entry = ttk.Entry(self.frequency_stage_frame, textvariable=self.stage_time_var, width=8)
        self.frequency_stage_second_label = ttk.Label(self.frequency_stage_frame, text="秒后", style="Card.TLabel")
        self.frequency_stage_interval_label = ttk.Label(self.frequency_stage_frame, text="频率", style="Card.TLabel")
        self.frequency_stage_interval_entry = ttk.Entry(self.frequency_stage_frame, textvariable=self.stage_interval_var, width=8)
        self.frequency_stage_ms_label = ttk.Label(self.frequency_stage_frame, text="ms", style="Card.TLabel")
        self.frequency_stage_hint = ttk.Label(settings_frame, text="例：时间 1.5，频率 50ms = 按住 1.5 秒后改为 50ms", style="CardHint.TLabel")

        self.frequency_stage_time_label.grid(row=0, column=0, sticky="w")
        self.frequency_stage_time_entry.grid(row=0, column=1, padx=(6, 8))
        self.frequency_stage_second_label.grid(row=0, column=2, sticky="w")
        self.frequency_stage_interval_label.grid(row=0, column=3, sticky="w", padx=(12, 0))
        self.frequency_stage_interval_entry.grid(row=0, column=4, padx=(6, 8))
        self.frequency_stage_ms_label.grid(row=0, column=5, sticky="w")
        self._toggle_frequency_stages()

        direction_frame = ttk.LabelFrame(frame, text="滑动方向", padding=8)
        direction_frame.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        direction_frame.columnconfigure(1, weight=1)

        ttk.Label(direction_frame, text="方向", style="Card.TLabel").grid(row=0, column=0, sticky="w")
        direction_box = ttk.Combobox(
            direction_frame,
            textvariable=self.direction_var,
            values=list(DIRECTION_ANGLES.keys()),
            width=14,
            state="readonly",
        )
        direction_box.grid(row=0, column=1, sticky="ew", padx=(10, 0))
        direction_box.bind("<<ComboboxSelected>>", lambda _event: self._select_direction_preset())

        ttk.Label(direction_frame, text="角度", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(direction_frame, textvariable=self.angle_var, width=12).grid(row=1, column=1, sticky="ew", padx=(10, 0), pady=(6, 0))

        quick_frame = ttk.Frame(direction_frame)
        quick_frame.grid(row=2, column=0, columnspan=2, pady=(6, 0))
        ttk.Button(quick_frame, text="左上", command=lambda: self._set_angle("左上", 225), width=7).grid(row=0, column=0, padx=1, pady=1)
        ttk.Button(quick_frame, text="上", command=lambda: self._set_angle("向上", 270), width=7).grid(row=0, column=1, padx=1, pady=1)
        ttk.Button(quick_frame, text="右上", command=lambda: self._set_angle("右上", 315), width=7).grid(row=0, column=2, padx=1, pady=1)
        ttk.Button(quick_frame, text="左", command=lambda: self._set_angle("向左", 180), width=7).grid(row=1, column=0, padx=1, pady=1)
        ttk.Button(quick_frame, text="停止", command=lambda: self._set_angle("停止", -1), width=7).grid(row=1, column=1, padx=1, pady=1)
        ttk.Button(quick_frame, text="右", command=lambda: self._set_angle("向右", 0), width=7).grid(row=1, column=2, padx=1, pady=1)
        ttk.Button(quick_frame, text="左下", command=lambda: self._set_angle("左下", 135), width=7).grid(row=2, column=0, padx=1, pady=1)
        ttk.Button(quick_frame, text="下", command=lambda: self._set_angle("向下", 90), width=7).grid(row=2, column=1, padx=1, pady=1)
        ttk.Button(quick_frame, text="右下", command=lambda: self._set_angle("右下", 45), width=7).grid(row=2, column=2, padx=1, pady=1)

        ttk.Label(direction_frame, textvariable=self.direction_status_var, style="CardHint.TLabel").grid(
            row=3, column=0, columnspan=2, sticky="w", pady=(4, 0)
        )

        custom_frame = ttk.Frame(direction_frame)
        custom_frame.grid(row=4, column=0, columnspan=2, sticky="w", pady=(4, 0))
        ttk.Label(custom_frame, text="精确 X", style="Card.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Entry(custom_frame, textvariable=self.dx_var, width=8).grid(row=0, column=1, padx=(6, 10))
        ttk.Label(custom_frame, text="Y", style="Card.TLabel").grid(row=0, column=2, sticky="w")
        ttk.Entry(custom_frame, textvariable=self.dy_var, width=8).grid(row=0, column=3, padx=(6, 0))

        hotkey_frame = ttk.LabelFrame(frame, text="开关热键", padding=8)
        hotkey_frame.grid(row=3, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        hotkey_frame.columnconfigure(0, weight=1)
        ttk.Entry(hotkey_frame, textvariable=self.hotkey_var, width=10).grid(row=0, column=0, sticky="ew")
        ttk.Button(hotkey_frame, text="录制热键", command=self.start_hotkey_capture).grid(row=0, column=1, padx=(8, 0))
        ttk.Label(hotkey_frame, textvariable=self.hotkey_hint_var, wraplength=360, style="CardHint.TLabel").grid(row=1, column=0, columnspan=2, sticky="w", pady=(4, 0))

        ttk.Button(frame, text="应用配置", command=self.apply_settings, style="Primary.TButton").grid(row=4, column=0, columnspan=2, sticky="ew", pady=(6, 0))
        ttk.Label(frame, textvariable=self.active_profile_var, style="Hint.TLabel").grid(row=5, column=0, columnspan=2, sticky="w", pady=(8, 0))
        ttk.Label(frame, textvariable=self.status_var, style="Hint.TLabel").grid(row=6, column=0, columnspan=2, sticky="w", pady=(3, 0))
        ttk.Label(frame, textvariable=self.left_status_var, style="Hint.TLabel").grid(row=7, column=0, columnspan=2, sticky="w", pady=(3, 0))

        author = "作者：寒枫    反馈群：573309536"
        ttk.Label(frame, text=author, style="Hint.TLabel").grid(row=8, column=0, columnspan=2, sticky="w", pady=(5, 0))

    def _resource_path(self, filename: str) -> Path:
        base_path = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
        return base_path / filename

    def _config_path(self) -> Path:
        if sys.platform.startswith("win"):
            base_dir = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
        elif sys.platform == "darwin":
            base_dir = Path.home() / "Library" / "Application Support"
        else:
            base_dir = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
        return base_dir / "MouseSlideController" / CONFIG_FILE_NAME

    def _load_config(self) -> dict[str, object]:
        path = self.config_path
        try:
            if not path.exists():
                return {}
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except Exception:
            pass
        return {}

    def _load_profiles(self, raw_profiles: object) -> list[ProfileConfig]:
        profiles: list[ProfileConfig] = []
        if not isinstance(raw_profiles, list):
            return profiles
        for item in raw_profiles:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name", "")).strip()
            switch_hotkey = self._normalize_hotkey(str(item.get("switch_hotkey", "")))
            if not name or not switch_hotkey:
                continue
            profiles.append(
                ProfileConfig(
                    name=name,
                    switch_hotkey=switch_hotkey,
                    direction=str(item.get("direction", "向下")),
                    angle_degrees=str(item.get("angle_degrees", "90")),
                    interval_preset=str(item.get("interval_preset", "30 ms")),
                    interval_ms=str(item.get("interval_ms", "30")),
                    enable_frequency_stages=bool(item.get("enable_frequency_stages", False)),
                    stage_time=str(item.get("stage_time", "1.5")),
                    stage_interval=str(item.get("stage_interval", "50")),
                )
            )
        return profiles

    def _active_profile_text(self) -> str:
        if self.active_profile_name:
            return f"当前配置：{self.active_profile_name}"
        return "当前配置：手动设置"

    def _save_config(self) -> None:
        data = {
            "hotkey": self._normalize_hotkey(self.hotkey_var.get()),
            "direction": self.direction_var.get(),
            "angle_degrees": self.angle_var.get().strip(),
            "interval_preset": self.interval_preset_var.get(),
            "interval_ms": self.interval_var.get().strip(),
            "enable_frequency_stages": self.enable_frequency_stages_var.get(),
            "stage_time": self.stage_time_var.get().strip(),
            "stage_interval": self.stage_interval_var.get().strip(),
            "dark_mode": self.dark_mode,
            "active_profile_name": self.active_profile_name,
            "profiles": [profile.__dict__ for profile in self.profiles],
        }
        try:
            self.config_path.parent.mkdir(parents=True, exist_ok=True)
            self.config_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        except Exception:
            pass

    def _apply_window_icon(self) -> None:
        ico_path = self._resource_path("app.ico")
        png_path = self._resource_path("app.png")
        if ico_path.exists():
            try:
                self.root.iconbitmap(str(ico_path))
                return
            except Exception:
                pass
        if png_path.exists():
            try:
                self.window_icon_image = tk.PhotoImage(file=str(png_path))
                self.root.iconphoto(True, self.window_icon_image)
            except Exception:
                pass

    def toggle_theme(self) -> None:
        self.dark_mode = not self.dark_mode
        self._apply_theme()
        self._save_config()

    def show_usage(self) -> None:
        messagebox.showinfo(
            "使用说明",
            "1. 设置滑动间隔，数值越小移动越快，例如 0.1 秒、30ms 或 12.5ms。\n"
            "2. 启用分阶频率后，填写时间和频率；例如时间 1.5、频率 50，表示按住 1.5 秒后改为 50ms。\n"
            "3. 选择方向，或直接输入 0-360 度角。\n"
            "4. 点击“录制热键”，按下想用作开关的键。\n"
            "5. 点击“配置”可以创建多套配置，每套配置都有自己的切换热键。\n"
            "6. 点击“开启”或按开关热键开启功能。\n"
            "7. 按住鼠标左键开始滑动，松开左键停止滑动。\n"
            "8. 再按一次开关热键或点击“关闭”即可关闭功能。\n\n"
            "月亮/太阳按钮用于切换黑白主题。",
        )

    def open_profiles_window(self) -> None:
        if hasattr(self, "profiles_window") and self.profiles_window.winfo_exists():
            self.profiles_window.lift()
            return

        window = tk.Toplevel(self.root)
        self.profiles_window = window
        window.title("配置管理")
        window.resizable(False, False)
        window.configure(bg=self.theme_colors["bg"])

        container = ttk.Frame(window, padding=10, style="App.TFrame")
        container.grid(row=0, column=0, sticky="nsew")

        left = ttk.Frame(container, style="App.TFrame")
        left.grid(row=0, column=0, sticky="ns")
        ttk.Label(left, text="配置列表", style="Title.TLabel").grid(row=0, column=0, sticky="w")
        self.profile_listbox = tk.Listbox(left, height=12, width=24, exportselection=False)
        self.profile_listbox.grid(row=1, column=0, sticky="ns", pady=(6, 0))
        self.profile_listbox.bind("<<ListboxSelect>>", lambda _event: self._load_selected_profile_to_form())

        right = ttk.Frame(container, style="App.TFrame")
        right.grid(row=0, column=1, sticky="nsew", padx=(12, 0))
        right.columnconfigure(1, weight=1)

        self.profile_name_var = tk.StringVar(value="")
        self.profile_hotkey_var = tk.StringVar(value="")
        self.profile_direction_var = tk.StringVar(value=self.direction_var.get())
        self.profile_angle_var = tk.StringVar(value=self.angle_var.get())
        self.profile_interval_preset_var = tk.StringVar(value=self.interval_preset_var.get())
        self.profile_interval_var = tk.StringVar(value=self.interval_var.get())
        self.profile_enable_stage_var = tk.BooleanVar(value=self.enable_frequency_stages_var.get())
        self.profile_stage_time_var = tk.StringVar(value=self.stage_time_var.get())
        self.profile_stage_interval_var = tk.StringVar(value=self.stage_interval_var.get())

        ttk.Label(right, text="配置名称", style="Card.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Entry(right, textvariable=self.profile_name_var, width=20).grid(row=0, column=1, sticky="ew", padx=(8, 0))
        ttk.Label(right, text="切换热键", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(right, textvariable=self.profile_hotkey_var, width=20).grid(row=1, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))
        ttk.Label(right, text="方向", style="Card.TLabel").grid(row=2, column=0, sticky="w", pady=(6, 0))
        profile_direction_box = ttk.Combobox(right, textvariable=self.profile_direction_var, values=list(DIRECTION_ANGLES.keys()), state="readonly", width=18)
        profile_direction_box.grid(row=2, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))
        profile_direction_box.bind("<<ComboboxSelected>>", lambda _event: self._select_profile_direction_preset())
        ttk.Label(right, text="角度", style="Card.TLabel").grid(row=3, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(right, textvariable=self.profile_angle_var, width=20).grid(row=3, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))
        ttk.Label(right, text="滑动间隔", style="Card.TLabel").grid(row=4, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(right, textvariable=self.profile_interval_var, width=20).grid(row=4, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))
        ttk.Label(right, text="间隔预设", style="Card.TLabel").grid(row=5, column=0, sticky="w", pady=(6, 0))
        ttk.Combobox(right, textvariable=self.profile_interval_preset_var, values=list(INTERVAL_PRESETS.keys()), state="readonly", width=18).grid(row=5, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))
        ttk.Checkbutton(right, text="启用分阶频率", variable=self.profile_enable_stage_var).grid(row=6, column=0, columnspan=2, sticky="w", pady=(6, 0))
        ttk.Label(right, text="分阶时间", style="Card.TLabel").grid(row=7, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(right, textvariable=self.profile_stage_time_var, width=20).grid(row=7, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))
        ttk.Label(right, text="分阶频率", style="Card.TLabel").grid(row=8, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(right, textvariable=self.profile_stage_interval_var, width=20).grid(row=8, column=1, sticky="ew", padx=(8, 0), pady=(6, 0))

        actions = ttk.Frame(container, style="App.TFrame")
        actions.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(10, 0))
        ttk.Button(actions, text="新建", command=self._new_profile_form).grid(row=0, column=0, padx=(0, 6))
        ttk.Button(actions, text="从当前设置填充", command=self._fill_profile_from_current).grid(row=0, column=1, padx=(0, 6))
        ttk.Button(actions, text="保存配置", command=self._save_profile_from_form, style="Primary.TButton").grid(row=0, column=2, padx=(0, 6))
        ttk.Button(actions, text="应用此配置", command=self._apply_selected_profile).grid(row=0, column=3, padx=(0, 6))
        ttk.Button(actions, text="删除配置", command=self._delete_selected_profile).grid(row=0, column=4, padx=(0, 6))
        ttk.Button(actions, text="关闭", command=window.destroy).grid(row=0, column=5)

        self._refresh_profile_listbox()
        self._fill_profile_from_current()

    def _refresh_profile_listbox(self) -> None:
        if not hasattr(self, "profile_listbox"):
            return
        self.profile_listbox.delete(0, tk.END)
        for profile in self.profiles:
            self.profile_listbox.insert(tk.END, f"{profile.name}    [{profile.switch_hotkey}]")

    def _selected_profile_index(self) -> int | None:
        if not hasattr(self, "profile_listbox"):
            return None
        selection = self.profile_listbox.curselection()
        if not selection:
            return None
        index = int(selection[0])
        if 0 <= index < len(self.profiles):
            return index
        return None

    def _load_selected_profile_to_form(self) -> None:
        index = self._selected_profile_index()
        if index is None:
            return
        self._set_profile_form(self.profiles[index])

    def _set_profile_form(self, profile: ProfileConfig) -> None:
        self.profile_name_var.set(profile.name)
        self.profile_hotkey_var.set(profile.switch_hotkey)
        self.profile_direction_var.set(profile.direction)
        self.profile_angle_var.set(profile.angle_degrees)
        self.profile_interval_preset_var.set(profile.interval_preset)
        self.profile_interval_var.set(profile.interval_ms)
        self.profile_enable_stage_var.set(profile.enable_frequency_stages)
        self.profile_stage_time_var.set(profile.stage_time)
        self.profile_stage_interval_var.set(profile.stage_interval)

    def _new_profile_form(self) -> None:
        next_name = f"配置{len(self.profiles) + 1}"
        self.profile_name_var.set(next_name)
        self.profile_hotkey_var.set("")
        self._fill_profile_from_current(keep_name=True)

    def _fill_profile_from_current(self, keep_name: bool = False) -> None:
        if not keep_name and not self.profile_name_var.get().strip():
            self.profile_name_var.set(f"配置{len(self.profiles) + 1}")
        self.profile_direction_var.set(self.direction_var.get())
        self.profile_angle_var.set(self.angle_var.get())
        self.profile_interval_preset_var.set(self.interval_preset_var.get())
        self.profile_interval_var.set(self.interval_var.get())
        self.profile_enable_stage_var.set(self.enable_frequency_stages_var.get())
        self.profile_stage_time_var.set(self.stage_time_var.get())
        self.profile_stage_interval_var.set(self.stage_interval_var.get())

    def _select_profile_direction_preset(self) -> None:
        direction = self.profile_direction_var.get()
        angle = DIRECTION_ANGLES.get(direction, 90.0)
        self.profile_angle_var.set(f"{angle:g}")

    def _profile_from_form(self) -> ProfileConfig | None:
        name = self.profile_name_var.get().strip()
        switch_hotkey = self._normalize_hotkey(self.profile_hotkey_var.get())
        if not name:
            messagebox.showerror("配置错误", "配置名称不能为空。")
            return None
        if not switch_hotkey:
            messagebox.showerror("配置错误", "切换热键不能为空。")
            return None
        if switch_hotkey == self.settings.hotkey:
            messagebox.showerror("配置错误", "切换热键不能和开关热键相同。")
            return None
        try:
            float(self.profile_angle_var.get().strip())
            self._parse_interval_ms(self.profile_interval_var.get().strip())
            if self.profile_enable_stage_var.get():
                self._parse_frequency_stage(self.profile_stage_time_var.get(), self.profile_stage_interval_var.get())
        except ValueError:
            messagebox.showerror("配置错误", "配置里的角度、频率和分阶频率必须是有效数字。")
            return None
        return ProfileConfig(
            name=name,
            switch_hotkey=switch_hotkey,
            direction=self.profile_direction_var.get(),
            angle_degrees=self.profile_angle_var.get().strip(),
            interval_preset=self.profile_interval_preset_var.get(),
            interval_ms=self.profile_interval_var.get().strip(),
            enable_frequency_stages=self.profile_enable_stage_var.get(),
            stage_time=self.profile_stage_time_var.get().strip(),
            stage_interval=self.profile_stage_interval_var.get().strip(),
        )

    def _save_profile_from_form(self) -> None:
        profile = self._profile_from_form()
        if profile is None:
            return
        selected_index = self._selected_profile_index()
        for index, existing in enumerate(self.profiles):
            if index != selected_index and existing.switch_hotkey == profile.switch_hotkey:
                messagebox.showerror("配置错误", f"热键 {profile.switch_hotkey} 已被配置“{existing.name}”使用。")
                return
            if index != selected_index and existing.name == profile.name:
                messagebox.showerror("配置错误", f"配置名称“{profile.name}”已经存在。")
                return
        if selected_index is None:
            self.profiles.append(profile)
        else:
            self.profiles[selected_index] = profile
        self._refresh_profile_listbox()
        self._save_config()
        messagebox.showinfo("配置已保存", f"已保存配置：{profile.name}")

    def _apply_selected_profile(self) -> None:
        index = self._selected_profile_index()
        profile = self.profiles[index] if index is not None else self._profile_from_form()
        if profile is None:
            return
        self._apply_profile(profile)

    def _delete_selected_profile(self) -> None:
        index = self._selected_profile_index()
        if index is None:
            messagebox.showerror("配置错误", "请先选择一个配置。")
            return
        profile = self.profiles[index]
        if not messagebox.askyesno("删除配置", f"确定删除配置“{profile.name}”吗？"):
            return
        del self.profiles[index]
        if self.active_profile_name == profile.name:
            self.active_profile_name = ""
            self.active_profile_var.set(self._active_profile_text())
        self._refresh_profile_listbox()
        self._save_config()

    def _apply_profile(self, profile: ProfileConfig) -> None:
        self.direction_var.set(profile.direction)
        self.angle_var.set(profile.angle_degrees)
        self.interval_preset_var.set(profile.interval_preset)
        self.interval_var.set(profile.interval_ms)
        self.enable_frequency_stages_var.set(profile.enable_frequency_stages)
        self.stage_time_var.set(profile.stage_time)
        self.stage_interval_var.set(profile.stage_interval)
        self.active_profile_name = profile.name
        self._toggle_frequency_stages()
        if self.apply_settings(show_message=False):
            with self.lock:
                if self.left_pressed:
                    self.left_press_started_at = time.monotonic()
                self.move_x_remainder = 0.0
                self.move_y_remainder = 0.0
            self.active_profile_var.set(self._active_profile_text())

    def _select_interval_preset(self) -> None:
        value = INTERVAL_PRESETS.get(self.interval_preset_var.get())
        if value is not None:
            self.interval_var.set(f"{value:g}")
            self.apply_settings(show_message=False)

    def _toggle_frequency_stages(self) -> None:
        if self.enable_frequency_stages_var.get():
            self.frequency_stage_frame.grid(row=3, column=0, columnspan=4, sticky="w", pady=(4, 0))
            self.frequency_stage_hint.grid(row=4, column=0, columnspan=4, sticky="w", pady=(4, 0))
        else:
            self.frequency_stage_frame.grid_remove()
            self.frequency_stage_hint.grid_remove()
        self.apply_settings(show_message=False)

    def _apply_theme(self) -> None:
        if self.dark_mode:
            bg = "#111827"
            panel_bg = "#1f2937"
            fg = "#f9fafb"
            muted = "#9ca3af"
            button_bg = "#374151"
            canvas_bg = "#0f172a"
            canvas_line = "#4b5563"
            ball = "#60a5fa"
            ball_outline = "#93c5fd"
        else:
            bg = "#ffffff"
            panel_bg = "#f6f7fb"
            fg = "#111827"
            muted = "#718096"
            button_bg = "#eef2f7"
            canvas_bg = "#f6f7fb"
            canvas_line = "#c4ccd6"
            ball = "#2563eb"
            ball_outline = "#1d4ed8"

        self.theme_colors = {
            "bg": bg,
            "fg": fg,
            "panel_bg": panel_bg,
            "canvas_bg": canvas_bg,
            "canvas_line": canvas_line,
            "muted": muted,
            "ball": ball,
            "ball_outline": ball_outline,
        }
        self.root.configure(bg=bg)
        self.style.configure("App.TFrame", background=bg)
        self.style.configure("Card.TFrame", background=panel_bg, relief="flat")
        self.style.configure("TFrame", background=bg)
        self.style.configure("TLabel", background=bg, foreground=fg, font=("Microsoft YaHei UI", 9))
        self.style.configure("Title.TLabel", background=bg, foreground=fg, font=("Microsoft YaHei UI", 13, "bold"))
        self.style.configure("Hint.TLabel", background=bg, foreground=muted, font=("Microsoft YaHei UI", 9))
        self.style.configure("Card.TLabel", background=panel_bg, foreground=fg, font=("Microsoft YaHei UI", 9))
        self.style.configure("CardHint.TLabel", background=panel_bg, foreground=muted, font=("Microsoft YaHei UI", 9))
        self.style.configure("TLabelframe", background=panel_bg, foreground=fg, relief="solid", borderwidth=1)
        self.style.configure("TLabelframe.Label", background=panel_bg, foreground=fg, font=("Microsoft YaHei UI", 10, "bold"))
        self.style.configure("TButton", background=button_bg, foreground=fg, font=("Microsoft YaHei UI", 9), padding=(7, 4))
        self.style.map("TButton", background=[("active", panel_bg)], foreground=[("active", fg)])
        self.style.configure("Primary.TButton", background="#2563eb", foreground="#ffffff", font=("Microsoft YaHei UI", 9, "bold"), padding=(10, 5))
        self.style.map("Primary.TButton", background=[("active", "#1d4ed8")], foreground=[("active", "#ffffff")])
        self.style.configure("TEntry", fieldbackground=panel_bg, foreground=fg, insertcolor=fg, padding=(6, 3))
        if hasattr(self, "theme_canvas"):
            self._draw_theme_icon()

    def _draw_theme_icon(self) -> None:
        colors = self.theme_colors
        canvas = self.theme_canvas
        canvas.configure(bg=colors["bg"])
        canvas.delete("all")
        if self.dark_mode:
            canvas.create_oval(7, 6, 25, 24, fill="#f9fafb", outline="#f9fafb")
            canvas.create_oval(14, 3, 30, 19, fill=colors["bg"], outline=colors["bg"])
        else:
            canvas.create_oval(10, 8, 24, 22, fill="#f59e0b", outline="#d97706", width=2)
            for x1, y1, x2, y2 in (
                (17, 1, 17, 5),
                (17, 25, 17, 29),
                (3, 15, 7, 15),
                (27, 15, 31, 15),
                (7, 5, 10, 8),
                (24, 22, 27, 25),
                (7, 25, 10, 22),
                (24, 8, 27, 5),
            ):
                canvas.create_line(x1, y1, x2, y2, fill="#f59e0b", width=2, capstyle=tk.ROUND)

    def start_hotkey_capture(self) -> None:
        with self.lock:
            self.capturing_hotkey = True
        self.hotkey_hint_var.set("正在录制：请按下想用作开关的键。")

    def _select_direction_preset(self) -> None:
        direction = self.direction_var.get()
        angle = DIRECTION_ANGLES.get(direction, 90.0)
        self.angle_var.set(f"{angle:g}")
        self.apply_settings(show_message=False)

    def _set_angle(self, direction: str, angle: float) -> None:
        self.direction_var.set(direction)
        self.angle_var.set(f"{angle:g}")
        self.apply_settings(show_message=False)

    def _update_direction_status(self, dx: float, dy: float) -> None:
        if dx == 0 and dy == 0:
            name = "停止"
        elif abs(dx) > abs(dy) * 1.8:
            name = "向右" if dx > 0 else "向左"
        elif abs(dy) > abs(dx) * 1.8:
            name = "向下" if dy > 0 else "向上"
        else:
            vertical = "下" if dy > 0 else "上"
            horizontal = "右" if dx > 0 else "左"
            name = f"{horizontal}{vertical}"
        self.direction_status_var.set(f"方向：{name}")

    def toggle_enabled(self) -> None:
        if not self.apply_settings(show_message=False):
            return
        with self.lock:
            self.enabled = not self.enabled
            if not self.enabled:
                self.left_pressed = False
                self.left_press_started_at = None
                self.move_x_remainder = 0.0
                self.move_y_remainder = 0.0

    def apply_settings(self, show_message: bool = True) -> bool:
        hotkey = self._normalize_hotkey(self.hotkey_var.get())
        if not hotkey:
            messagebox.showerror("配置错误", "热键不能为空。")
            return False
        self.hotkey_var.set(hotkey)
        conflicting_profile = next((profile for profile in self.profiles if profile.switch_hotkey == hotkey), None)
        if conflicting_profile:
            messagebox.showerror("配置错误", f"开关热键不能和配置“{conflicting_profile.name}”的切换热键相同。")
            return False

        try:
            interval_ms = self._parse_interval_ms(self.interval_var.get().strip())
            dx, dy, angle_degrees = self._resolve_direction()
            frequency_stages = (
                self._parse_frequency_stage(self.stage_time_var.get(), self.stage_interval_var.get())
                if self.enable_frequency_stages_var.get()
                else ()
            )
        except ValueError:
            messagebox.showerror("配置错误", "滑动间隔、分阶频率、角度、X 和 Y 必须是有效数字。")
            return False

        if interval_ms < 1:
            messagebox.showerror("配置错误", "移动间隔至少为 1ms。")
            return False

        with self.lock:
            self.settings = MoveSettings(
                hotkey=hotkey,
                dx=dx,
                dy=dy,
                interval_ms=interval_ms,
                angle_degrees=angle_degrees,
                frequency_stages=frequency_stages,
            )

        if show_message:
            self.active_profile_name = ""
        self.active_profile_var.set(self._active_profile_text())
        self._save_config()
        if show_message:
            messagebox.showinfo("配置已应用", "新的热键、方向和频率已经生效。")
        return True

    @staticmethod
    def _parse_interval_ms(raw_value: str) -> float:
        value = raw_value.strip().lower().replace(" ", "")
        if value.endswith("ms"):
            return float(value[:-2])
        if value.endswith("毫秒"):
            return float(value[:-2])
        if value.endswith("s"):
            return float(value[:-1]) * 1000
        if value.endswith("秒"):
            return float(value[:-1]) * 1000
        return float(value)

    @staticmethod
    def _parse_frequency_stage(time_value: str, interval_value: str) -> tuple[tuple[float, float], ...]:
        start_second = float(time_value.strip().lower().replace("秒", "").replace("s", ""))
        interval_ms = MouseControllerApp._parse_interval_ms(interval_value.strip())
        if start_second < 0 or interval_ms < 1:
            raise ValueError("Invalid frequency stage")
        return ((start_second, interval_ms),)

    def _resolve_direction(self) -> tuple[float, float, float]:
        angle_degrees = float(self.angle_var.get().strip())
        if angle_degrees < 0:
            dx = 0.0
            dy = 0.0
        else:
            normalized_angle = angle_degrees % 360
            angle = radians(normalized_angle)
            dx = cos(angle) * MOVE_PER_TICK
            dy = sin(angle) * MOVE_PER_TICK
            angle_degrees = normalized_angle
            self.angle_var.set(f"{angle_degrees:g}")
        self._update_direction_status(dx, dy)
        self.dx_var.set(f"{dx:.2f}")
        self.dy_var.set(f"{dy:.2f}")
        return dx, dy, angle_degrees

    def _start_listeners(self) -> None:
        self.keyboard_listener = keyboard.Listener(on_press=self._on_key_press, on_release=self._on_key_release)
        self.mouse_listener = mouse.Listener(on_click=self._on_mouse_click)
        self.keyboard_listener.start()
        self.mouse_listener.start()

    def _on_key_press(self, key: keyboard.Key | keyboard.KeyCode, *_: object) -> None:
        key_name = self._key_to_name(key)
        with self.lock:
            if self.capturing_hotkey:
                self.capturing_hotkey = False
                self.ui_events.put(("capture_hotkey", key_name))
                return
            profile = self._profile_for_hotkey(key_name)
            if profile and key_name not in self.profile_hotkeys_down:
                self.profile_hotkeys_down.add(key_name)
                self.ui_events.put(("profile_hotkey", key_name))
                return
            if key_name != self.settings.hotkey or self.hotkey_down:
                return
            self.hotkey_down = True
            self.ui_events.put(("toggle", None))

    def _on_key_release(self, key: keyboard.Key | keyboard.KeyCode, *_: object) -> None:
        key_name = self._key_to_name(key)
        with self.lock:
            if key_name == self.settings.hotkey:
                self.hotkey_down = False
            self.profile_hotkeys_down.discard(key_name)

    def _profile_for_hotkey(self, hotkey: str) -> ProfileConfig | None:
        for profile in self.profiles:
            if profile.switch_hotkey == hotkey:
                return profile
        return None

    def _on_mouse_click(self, _x: int, _y: int, button: mouse.Button, pressed: bool, *_: object) -> None:
        if button != mouse.Button.left:
            return
        with self.lock:
            was_pressed = self.left_pressed
            self.left_pressed = pressed and self.enabled
            if self.left_pressed and not was_pressed:
                self.left_press_started_at = time.monotonic()
                self.move_x_remainder = 0.0
                self.move_y_remainder = 0.0
            elif not self.left_pressed:
                self.left_press_started_at = None
                self.move_x_remainder = 0.0
                self.move_y_remainder = 0.0

    def _move_loop(self) -> None:
        while self.running:
            with self.lock:
                enabled = self.enabled
                left_pressed = self.left_pressed
                settings = self.settings
                started_at = self.left_press_started_at

            if enabled and left_pressed and (settings.dx != 0 or settings.dy != 0):
                dx, dy = self._next_move_delta(settings.dx, settings.dy)
                if dx != 0 or dy != 0:
                    self.mouse_controller.move(dx, dy)

            interval_ms = self._current_interval_ms(settings, started_at)
            time.sleep(max(interval_ms, 1.0) / 1000)

    @staticmethod
    def _current_interval_ms(settings: MoveSettings, started_at: float | None) -> float:
        if not settings.frequency_stages or started_at is None:
            return settings.interval_ms

        elapsed = max(time.monotonic() - started_at, 0.0)
        active_interval_ms = settings.interval_ms
        for start_second, interval_ms in settings.frequency_stages:
            if elapsed >= start_second:
                active_interval_ms = interval_ms
            else:
                break
        return active_interval_ms

    def _next_move_delta(self, dx: float, dy: float) -> tuple[int, int]:
        with self.lock:
            self.move_x_remainder += dx
            self.move_y_remainder += dy
            move_x = int(self.move_x_remainder)
            move_y = int(self.move_y_remainder)
            self.move_x_remainder -= move_x
            self.move_y_remainder -= move_y
        return move_x, move_y

    def _process_ui_events(self) -> None:
        while not self.ui_events.empty():
            event_name, value = self.ui_events.get()
            if event_name == "toggle":
                self.toggle_enabled()
            elif event_name == "capture_hotkey" and value:
                self.hotkey_var.set(self._normalize_hotkey(value))
                self.apply_settings(show_message=False)
                self.hotkey_hint_var.set(f"当前热键：{self.hotkey_var.get()}。软件在后台也能使用这个热键。")
            elif event_name == "profile_hotkey" and value:
                profile = self._profile_for_hotkey(value)
                if profile:
                    self._apply_profile(profile)
                    self.hotkey_hint_var.set(f"已切换配置：{profile.name}。")

        if self.running:
            self.root.after(50, self._process_ui_events)

    def _refresh_status(self) -> None:
        with self.lock:
            enabled = self.enabled
            left_pressed = self.left_pressed
            settings = self.settings

        self.status_var.set(
            f"状态：{'开启' if enabled else '关闭'} | 热键：{settings.hotkey} | 坐标：({settings.dx:.2f}, {settings.dy:.2f}) | {'分阶频率' if settings.frequency_stages else f'间隔：{settings.interval_ms:g}ms'}"
        )
        self.toggle_button_text.set("关闭" if enabled else "开启")
        self.left_status_var.set(f"左键：{'按下，正在滑动' if left_pressed else '未按下'}")

        if self.running:
            self.root.after(200, self._refresh_status)

    @staticmethod
    def _normalize_hotkey(raw_value: str) -> str:
        value = raw_value.strip().lower().replace(" ", "")
        if value.startswith("key."):
            value = value[4:]
        return HOTKEY_ALIASES.get(value, value)

    @classmethod
    def _key_to_name(cls, key: keyboard.Key | keyboard.KeyCode) -> str:
        if isinstance(key, keyboard.KeyCode) and key.char:
            return cls._normalize_hotkey(key.char)
        vk = getattr(key, "vk", None)
        if isinstance(vk, int):
            if 48 <= vk <= 57:
                return str(vk - 48)
            if 96 <= vk <= 105:
                return str(vk - 96)
        name = getattr(key, "name", None)
        if name:
            return cls._normalize_hotkey(str(name))
        return cls._normalize_hotkey(str(key))

    def close(self) -> None:
        self.apply_settings(show_message=False)
        self.running = False
        if self.keyboard_listener:
            self.keyboard_listener.stop()
        if self.mouse_listener:
            self.mouse_listener.stop()
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    MouseControllerApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
