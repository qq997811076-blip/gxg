# IP Speed Test Android

这是将原有 Go 命令行测速程序包装成 Android 中文界面的工程。原始 Go 核心位于 `app/src/main/assets/myspeed.go`，构建时直接编译该文件，不运行 gofmt。

## 构建 APK
GitHub Actions → Build APK → Run workflow。工作流只有一个串行 job，不会并行启动多个构建任务。完成后下载 `ipspeed-apk` artifact。

## 使用说明
- 主界面可设置 HTTPS 测速地址、连通性并发、下载并发、延迟最低 IP 数量、最低显示速度。
- IP 来源页可导入 TXT/CSV，编辑网络 URL 列表并分别启用本地文件和网络来源。
- TXT 按行读取：忽略行首空白，只接受行首 IPv4，尾部注释/符号忽略；CSV 从字段中识别 IPv4。合并后去重。
- 原核心只输出速度不低于 0.5 MiB/s 的结果，因此界面阈值可提高但不能显示核心已丢弃的低于 0.5 MiB/s 的结果。
- Android 通过独立适配层调用原命令行程序；未把 UI 代码插入测速函数。

## 核心不变说明
`myspeed.go` 文件由用户在对话中提供的源码文本保存。若需要证明与手机本地原文件逐字节一致，请在构建前将本地原文件与仓库文件进行 SHA-256 对比。此工程不会对该文件执行 gofmt。
