# 应用内网页链接

“设置 → 通用 → 应用内打开网页”默认开启，使用本地配置键 open_links_in_app，不要求 Root 或 LSPosed。修改在下一次点击链接时生效；关闭后交由系统处理。

聊天 Markdown、助手交互和运行结果浮层的网页链接，以及设置中的项目和反馈页面，统一通过 AppLinkOpener 打开。HTTP(S) 链接采用 Android Custom Tabs：浏览器提供带地址栏、关闭按钮及原生菜单的全屏浏览页面。由 Chrome 提供时，用户可通过菜单主动选择“在 Chrome 中打开”；实际样式与菜单文案由提供浏览器决定。

优先系统默认且支持 Custom Tabs 的浏览器；若默认浏览器不支持，再选择已安装的支持提供者。打开时指定提供者包名并设置 Custom Tabs intent，避免初始 URL 被其他应用截走。请求关闭初始跳转链中的外部默认处理。具体页面内导航行为仍由浏览器实现。

按用户要求，没有支持提供者时直接使用系统打开链接，不弹兼容性提示。关闭开关、非 HTTP(S) 链接及显式外部打开同样使用原系统处理。

主界面和浮层复用 AppUriHandler；Agent 自动化使用已有 AgentBrowserSession。账号授权、更新对话框中的“去下载”、Agent 浏览器的外部打开按钮和 open_uri 工具保留原流程。

项目新增 AndroidX Browser 1.10.0 依赖。主机测试覆盖提供者选择、Custom Tabs intent、缺失提供者回退、开关即时生效及 Activity/service 调用上下文。主机测试、构建和静态验签不等于真机 Chrome 菜单、返回手势及网页兼容性验收。

官方参考：[Custom Tabs](https://developer.chrome.com/docs/android/custom-tabs)、[AndroidX Browser](https://developer.android.com/jetpack/androidx/releases/browser)。
