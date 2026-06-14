# 打包成 Windows 软件

推荐使用 `PyInstaller`，它可以把 `mouse_controller.py` 打包成单个 `.exe` 文件。

## 准备软件

1. 安装 Python 3.11 或更新版本。
2. 安装时勾选 `Add python.exe to PATH`。
3. 下载地址：`https://www.python.org/downloads/`

## 一键打包

在 Windows 上打开 `desktop_mouse_controller` 文件夹，优先双击：

```text
build_windows.cmd
```

也可以双击：

```text
build_windows.bat
```

脚本使用纯英文输出，避免 Windows CMD 因中文编码把命令解析坏。它会打开一个命令行窗口并显示进度。如果失败，会停在错误页面，并把完整日志写到：

```text
build_windows.log
```

打包完成后，生成的软件在：

```text
dist\MouseSlideController.exe
```

## 手动打包

也可以在当前目录打开命令行，依次执行：

```bash
# 安装程序依赖
python -m pip install -r requirements.txt

# 安装打包工具
python -m pip install pyinstaller

# 打包成无控制台窗口的 exe
python -m PyInstaller --onefile --windowed --name MouseSlideController mouse_controller.py
```

## 自定义图标

Windows 的 exe 图标建议使用 `.ico` 格式。操作步骤：

1. 准备一张正方形图片，例如 `png`。
2. 用在线工具或图片软件转换成 `.ico`，建议包含 `256x256`、`128x128`、`64x64`、`32x32`、`16x16` 多尺寸。
3. 把图标文件放到 `desktop_mouse_controller` 文件夹里。
4. 推荐直接放 `app.png`，脚本会自动转换；也可以提前转换成：

```text
app.ico
```

5. 重新运行：

```text
build_windows.cmd
```

脚本会自动检测 `app.png` 或 `app.ico`。如果只有 `app.png`，会自动生成 `app.ico`，再把图标写入打包后的 `MouseSlideController.exe`。

新版脚本也会把 `app.ico/app.png` 一起打进 exe，程序窗口左上角和任务栏会尽量使用同一张图标。

注意：把 `png`、`jpg` 直接改名成 `app.ico` 会导致打包失败或退回默认图标。需要使用图片转换工具生成真正的 `.ico` 文件。

如果你已经有 Python，也可以用 Pillow 把 png 转成 ico：

```bash
# 安装 Pillow
python -m pip install pillow

# 把 app.png 转成多尺寸 app.ico
python -c "from PIL import Image; img=Image.open('app.png'); img.save('app.ico', sizes=[(256,256),(128,128),(64,64),(32,32),(16,16)])"
```

转换完成后重新运行：

```text
build_windows.cmd
```

手动打包时可以这样指定图标：

```bash
python -m PyInstaller --onefile --windowed --icon=app.ico --name MouseSlideController mouse_controller.py
```

如果 Windows 资源管理器里还是显示旧图标，通常是系统图标缓存没有刷新。可以把 exe 换一个文件名，或者重启资源管理器后再看。

## 运行失败处理

- 双击 `build_windows.cmd` 没有任何窗口时，右键它选择“编辑”，确认文件内容存在；也可以在地址栏输入 `cmd` 回车，然后执行 `build_windows.cmd`。
- 如果命令行提示找不到 Python，重新运行 Python 安装包，勾选 `Add python.exe to PATH` 后选择 Modify 或 Repair。
- 如果提示缺少 `pynput`，先执行 `python -m pip install -r requirements.txt`。
- 如果双击后没有反应，在当前目录打开命令行执行 `python mouse_controller.py`，查看具体报错。
- 某些安全软件会拦截全局键鼠监听，需要允许该程序访问键盘和鼠标输入。
- macOS 需要在系统设置里给终端或打包后的 App 授予辅助功能权限。
- Linux 的 Wayland 桌面可能限制全局监听，X11 环境兼容性更好。
