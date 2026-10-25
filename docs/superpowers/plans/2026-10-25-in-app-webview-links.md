# Custom Tabs 链接替换计划

用户已确认：保留现有开关与统一链接入口，普通网页改用 Custom Tabs，移除新增的自建 WebView 页面。

实现范围：
- 使用 AndroidX Browser 1.10.0；继续在 main 派生的 codex/in-app-webview-links 干净功能分支中工作。
- 优先系统默认且支持 Custom Tabs 的浏览器，再查找其他支持提供者；显式指定包名，以 Custom Tabs intent 启动。
- 默认开启现有 open_links_in_app；关闭、非 HTTP(S) 和显式外部打开沿用原系统处理。
- 按用户后续指示：无提供者时直接交给系统浏览器，不显示兼容性提示。
- 复用现有主题和运行浮层的 AppUriHandler；不改 Agent 工具 WebView、授权及更新下载流程。
- 删除 LinkWebViewActivity 源码、Manifest 注册、专用文案和 WebView 专属测试，更新功能文档。

验证步骤：
1. 新测试先验证旧实现不满足 provider 选择、Custom Tabs intent、缺失 provider 直接系统浏览器回退及旧 Activity 删除等行为。
2. 实现后验证上述测试、配置即时生效、关闭开关与非网页路由，以及 Activity/service 两类调用上下文。
3. 独立 review 并处理明确问题；验证源码与 Manifest 中不再存在新增的 WebView 页面。
4. 提交功能分支，合并到 custom；确认原有 custom 状态与链接入口保留。
5. 对合并状态运行定向回归、Release/R8 和 lint，使用已经修正的本地脚本进行独立 debug 签名；验证证书、非 debuggable、对齐与哈希。

边界：浏览器提供页面、三点菜单和“在浏览器中打开”操作；具体文案随提供浏览器变化。本轮不安装或操作 Android 设备，不推送远程。
