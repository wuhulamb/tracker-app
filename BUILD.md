# 构建与发布

## 环境

- Android SDK（`local.properties` 中 `sdk.dir` 指向本机 SDK）
- JDK 17

## 本地构建

```bash
# 调试版（debug 签名）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 运行单元测试（纯逻辑，无需设备）
./gradlew :app:testDebugUnitTest

# 发布版（需签名配置，见下）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

APK 只打包 `arm64-v8a` + `armeabi-v7a`（去掉模拟器专用的 x86/x86_64），Release 约 30 MB。

## 发布签名

`app/build.gradle.kts` 从项目根目录的 `keystore.properties` 读取签名（该文件**不入库**）：

```properties
storeFile=keystore/release.jks
storePassword=***
keyAlias=locationtracker
keyPassword=***
```

生成密钥：

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/release.jks -storetype PKCS12 \
  -alias locationtracker -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass <密码> -keypass <密码> -dname "CN=LocationTracker, O=Personal, C=CN"
```

> 密钥与密码务必妥善备份——丢失后无法再以同一签名更新应用。

## 发布流程（GitHub Release）

推 `v*` tag 会触发 `.github/workflows/release.yml`：跑单测 → 构建签名 APK → 创建 Release。

```bash
# 1) 改 app/build.gradle.kts 的 versionName / versionCode
# 2) 提交推送
git add -A && git commit -m "release: v0.x.0" && git push origin main
# 3) 打 tag（自动构建并发布 APK）
git tag v0.x.0 && git push origin v0.x.0
```

首次使用需在仓库 `Settings → Secrets and variables → Actions` 配置：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/release.jks` 的输出 |
| `STORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | `locationtracker` |
| `KEY_PASSWORD` | keystore 密码 |
