# 等离子体果园 · 订单后台 Android App

[苹果订单系统](https://apple.plaseco.tech/admin) 的员工端安卓 App，基于 Capacitor 6 封装，直接加载 https://apple.plaseco.tech/admin，网页更新后 App 自动同步，一般不需要重新安装。

- 包名：`tech.plaseco.appleorder`，应用名：等离子体果园
- 原生功能：导出 Excel / 图片下载（系统下载管理器，保存到「下载/等离子体果园」）、拍照上传、打印拣货单和发货单（系统打印服务，可另存为 PDF）、返回键后退、断网提示
- 权限：网络、相机（上传照片）、通知、开机自启和后台运行（订单提醒，员工自己在 App「我的 → 消息通知」开启）；Android 9 及以下下载文件时需要存储权限
- 订单提醒：国内收不到谷歌推送，App 以前台服务每分钟向服务器 /api/notify/pull 取一次新消息（NotifyService）
- 编译：推送到 `main` 分支后，GitHub Actions 自动编译签名版 APK 并发布到 Releases；最新版固定地址：
  `https://github.com/Fireooout/apple-order-app/releases/latest/download/appleorder.apk`
- 签名：`signing/release.jks.enc` 是 AES-256 加密的签名证书（RSA 4096，v1/v2/v3 签名），编译时用仓库 Secret `KEYSTORE_PASSWORD` 解密。证书原件与密码只保存在管理员本地，请勿丢失：丢失后新版本无法覆盖安装旧版本。
