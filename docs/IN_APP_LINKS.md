# 应用内网页链接

“设置 → 通用 → 应用内打开网页”默认开启，配置保存在 Eta 本地，不要求 Root 或 LSPosed。修改在下一次点击链接时生效；关闭后交由系统选择的浏览器或应用处理。

聊天 Markdown、助手交互和运行结果浮层中的网页链接，以及设置中的项目和反馈页面，统一通过 `AppLinkOpener` 打开。HTTP(S) 链接使用系统 WebView，在独立的 `LinkWebViewActivity` 中浏览。Activity 不对外导出，不导航或挂载 Agent 工具使用的共享 WebView。

网页工具栏显示站点与当前地址，支持刷新、返回网页历史及显式“在其他应用中打开”。退出后销毁本次 WebView；Activity 重建时恢复浏览历史。JavaScript 和 DOM storage 用于普通网站；关闭 file/content 访问，不注入原生 JavaScript 接口，不绕过 TLS 错误。普通 HTTP(S) 跳转留在 WebView 内，网页仅可通过顶层用户点击调用邮箱、电话、短信或地图应用。

已有账号授权、更新对话框中的“去下载”、Agent 浏览器的显式外部打开按钮和 Agent 的 `open_uri` 工具仍沿用原流程。该页面提供基本浏览功能；下载、文件上传、全屏视频及网站要求的外部浏览器能力不在本次实现范围，可使用工具栏的外部打开按钮。

实现选择：复用已有本地开关和偏好控件，并为 Compose 主题及独立运行浮层提供同一个 URI handler。直接复用 Agent 的浏览实例会改变自动化中的页面；浏览器 Custom Tabs 会依赖外部浏览器，因此本功能采用独立系统 WebView。

验证使用 Robolectric 链接路由、设置即时生效、Activity 导出限制和 WebViewClient 导航测试，并对 Markdown、配置和导航执行回归检查。构建与主机测试不等于真机网页、OEM 返回手势或外部授权兼容性验收。

Android 官方参考：[WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)、[WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient)。
