# 背景
一个利用二维码从远程桌面下载文件的工具。

当前，云桌面等技术的运用日益广泛，我们通过一个远程桌面工具即可实现远程开发和运维。
为了保证数据不泄露，远程桌面往往是禁用了拷贝文件到本地的功能。

然而，大部分产商在保证安全的时候，却没有配套的措施能够满足高效运维的需求：

例如，当我们通过云桌面部署的服务发生了异常并需要紧急修复，我们需要把日志或者运行dump文件拿到本地来分析，
云桌面却没有配备日志导出功能，这时候只能求爷爷告奶奶的去联系运维打车过来帮你拷文件，或者打开一个UltraEdit等软件手工抄写下来。。无论如何，这都要花掉很长时间。

此时，我们可以用这个工具，模仿打开UltraEdit对着屏幕抄写的过程，将日志文件"抄写"到本地

# 工作原理
远程桌面上的文件 -> 生成二维码图像 -> 读图像中的数据 -> 存本地文件

# 使用说明

下载最新的包
https://github.com/yuhuoji/qrtransfer/releases/download/v1.0/qrtransfer_v1.0.zip

然后[点击这里](doc/manual.md)按步骤配置和启动

# 统一命令行（推荐）

`qrtransfer-cli` 是统一的发送/接收程序，默认支持 ZIP、图片、PDF、Markdown、JSON 等任意文件。发送端和接收端都需要 JDK 11；如果其他项目使用 JDK 8，可以仅在启动命令中指定 JDK 11 的 `java` 完整路径，不需要修改系统默认环境。

构建：

```bash
mvn package
```

构建产物：

```text
qrtransfer-cli/target/qrtransfer-cli-1.0-SNAPSHOT.jar
```

## 启动顺序

1. 云桌面先启动发送端，等待文件头二维码显示。
2. 本机启动接收端。接收端默认倒计时 5 秒，期间切回云桌面并激活发送端窗口。
3. 传输期间保持发送端二维码完整可见，不要切换到其他窗口。

Windows/PowerShell 发送任意文件：

```powershell
cd qrtransfer-cli\target
java -jar .\qrtransfer-cli-1.0-SNAPSHOT.jar send `
  --input "<输入文件路径>" `
  --profile balanced
```

如果系统默认是 JDK 8，可仅为本程序指定 JDK 11：

```powershell
& "<JDK11安装目录>\bin\java.exe" -jar .\qrtransfer-cli-1.0-SNAPSHOT.jar send `
  --input "<输入文件路径>" `
  --profile balanced
```

Mac/Linux 接收：

```bash
cd qrtransfer-cli/target
java -jar ./qrtransfer-cli-1.0-SNAPSHOT.jar receive \
  --output "<输出文件路径>" \
  --profile balanced
```

目标已存在时增加 `--overwrite`。需要严格限制为 UTF-8 文本时，两端都可增加 `--text`；不传 `--text` 即按任意二进制文件处理。

## 速度预设与自适应

| profile | 二维码 | 初始页面 | 页面范围 | 截图等待范围 | 超时 |
|---|---:|---:|---:|---:|---:|
| `safe` | 512px | 1000B | 512–1600B | 200–1200ms | 2500ms |
| `balanced`（默认） | 512px | 1600B | 512–2000B | 80–800ms | 1500ms |
| `fast` | 640px | 1800B | 384–2100B | 40–500ms | 900ms |

发送端每连续成功 4 页增加 128 字节；页面超时或无法识别时按 0.7 倍降低密度并重传同一文件偏移。接收端根据远程桌面的实际刷新耗时自动调整截图等待，不会在二维码未变化时连续翻页。

发送端参数：

- `--input <路径>`：明确指定输入文件。
- `--text`：发送前验证输入是 UTF-8。
- `--profile safe|balanced|fast`：选择预设。
- `--qr-size <像素>`：二维码实际显示尺寸。
- `--min-page-size`、`--initial-page-size`、`--max-page-size`：页面自适应范围。
- `--fixed-page-size <字节>`：固定页面大小，关闭载荷自动升降。
- `--legacy`：生成与原版接收端兼容的二维码页。

接收端参数：

- `--output <路径>`：明确指定最终输出文件。
- `--text`：完成后严格验证 UTF-8。
- `--overwrite`：允许覆盖已存在目标。
- `--profile safe|balanced|fast`：选择预设。
- `--start-delay <秒>`：开始读取前的切换窗口倒计时，默认 5 秒。
- `--initial-delay`、`--min-delay`、`--max-delay`：自适应截图等待时间（毫秒）。
- `--frame-timeout <毫秒>`：新页面等待超时。
- `--legacy`：接收原版发送端协议；可用 `--page-delay` 设置原版固定等待。

显式参数优先于 profile 预设。吞吐量主要由“实际每页有效字节 ÷ 每页确认周期”决定；二维码过密或等待过短都会触发降速，不能只追求单个参数的最大值。

Adaptive V2 传输成功或失败后会在发送窗口和接收终端输出简短统计，包括耗时、有效数据量、平均速度、成功页数、识别失败、重复页、页序错误、超时、重传与降密次数。统计仅显示在本次运行界面中，默认不写入独立日志文件；逐页事件仍可在界面实时查看。

## 原版兼容

新发送端配原版接收端：

```powershell
java -jar .\qrtransfer-cli-1.0-SNAPSHOT.jar send `
  --input "<输入文件路径>" `
  --legacy
```

新接收端配原版发送端：

```bash
java -jar ./qrtransfer-cli-1.0-SNAPSHOT.jar receive \
  --output "<输出文件路径>" \
  --legacy \
  --page-delay 500
```

V2 默认模式必须由新的统一 CLI 两端配对使用。协议、确认键、自适应算法和失败恢复细节见[自适应 V2 协议说明](doc/adaptive-protocol.md)。

## 完整性

V2 每页使用 CRC32C 校验页号、偏移和载荷，完成后校验文件大小与 MD5。接收内容先写入临时 `.part` 文件，全部校验通过后才原子移动为最终文件；失败、中断或校验不一致时不会发布最终输出。本功能不提供加密或身份认证。

# 旧版剪贴板文本桥接

`clipboard-sender` 与 `clipboard-receiver` 保留原用法，只接受 UTF-8 纯文本。新使用场景建议改用统一 CLI，并通过 `--text` 获得相同的文本校验保护。

# 上游来源与开源许可

本项目基于 wowtools 的 [qrtransfer](https://gitee.com/wowtools/qrtransfer) 开发；原始发布包可见 [codingmiao/qrtransfer v1.0](https://github.com/codingmiao/qrtransfer/releases/tag/v1.0)。

原项目采用 [Apache License 2.0](LICENSE) 开源。本仓库保留原有许可证与声明；后续修改将在提交记录中明确说明。


# 声明

## 如果你是运维/开发等角色
这个工具是用来方便运维的同学快速排查文件的，不是拿来偷取公司机密的，你只应该拿它来传输你可以对着屏幕抄写下来的文件，请谨记。
这个软件的性能十分差劲，只有5kb/s左右的最高传输速度，传输过程会比较长，是很容易被抓到的，不要有侥幸心理。

## 如果你是云桌面的产商/公司机密管理者等角色
担心这个软件会泄露机密，那你可以通过定期抓取屏幕截图的方式来分析用户是否有违规行为（包括前面说的用UltraEdit等软件手工抄写下来等行为）。
这个软件的性能十分差劲，只有5kb/s左右的最高传输速度，传输过程会比较长，是很容易抓到不正当行为的。
同时，也请思考这个软件在什么情况下会出现在你的管辖范围内，是缺乏完善的开发运维工具体系？是管理机制逼的太紧？还是没有应急预案？因为这个软件的目的就是为了辅助快速通过日志等文件来排查紧急问题。

## 再次强调

本软件使用读取屏幕图像的方式从远程桌面拷贝文件，其运作原理与您用ByteEdit、UltraEdit等软件从远程桌面查看并抄写字节到本地文件是一样的，并不具备泄密特性。

但是，本软件仅供拷贝运行命令、分析参数等非敏感信息，作紧急排查问题等用途。

使用本软件时，请严格准守相关法律法规及贵公司各项规定，不要拷被任何涉密文件。

如您擅自违反上述法律法规及规定，造成的任何责任将完全由您自行承担。
