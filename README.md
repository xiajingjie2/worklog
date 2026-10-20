# 工作日志（WorkLog）

拍一张照片或录一段视频，自动落一条「时间 + 地点 + 媒体 + 一句备注」的记录；收工点一下，按时间线排成 Word 文档，直接分享给微信或邮件。

> 这个版本是 **MVP**：拍照记录、语音转文字备注、按日回看、导出 .docx。
> 目标只有一个 —— 单条记录 10 秒内记完，不需要填表。

---

## 一、它做什么

```
打开 App ──► 直接是相机（不经过首页）
              │
              ├─ 快门 ──► 存照片 + 抓时间/坐标 ──► 半屏浮层「补一句」（可跳过）
              └─ 录像 ──► 跳系统相机，录完自动导入 + 抽首帧做封面
                             │
「日志」页 ──► 按日期翻 ──► 导出 Word ──► 系统分享
```

- **不用填表**：时间、坐标、地名全自动；人工只补一句话，且可以跳过。
- **语音备注**：浮层里点麦克风说一句，走系统语音识别转成文字（无需额外权限）。
- **离线**：数据全部在应用私有目录，不联网（除了系统语音识别本身可能需要联网）。
- **导出**：手写 OOXML 生成 `.docx`，图片自动缩放嵌入，零第三方库。

## 二、构建

### 路线 A：GitHub Actions 云构建（推荐，不用装任何东西）

1. 新建一个空的 GitHub 仓库（Private 也可以）。
2. 把本目录整个推上去：

   ```bash
   cd worklog-android
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的账号>/<仓库名>.git
   git push -u origin main
   ```

3. 推完 Actions 自动开始跑（`Build APK`）。
4. 打开仓库的 **Actions → 最新一次运行 → Artifacts → worklog-debug-apk**，下载解压得到 `app-debug.apk`。

> 首次构建大概率会失败——因为这份代码没有在本机编译验证过。把报错日志发我，我按行修。

### 路线 B：本地构建

- 安装 [Android Studio](https://developer.android.com/studio)，用 `Open` 打开本目录。
- 或者命令行（需 JDK 17 + Android SDK）：

  ```bash
  gradle :app:assembleDebug
  ```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

> 首次用命令行构建前，先 `gradle wrapper --gradle-version 8.9` 生成 wrapper，之后就能用 `./gradlew`。

## 三、安装到手机

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / 数据线 / 网盘都行）。
2. 点击安装，允许「安装未知来源应用」。
3. 首次打开会要**相机权限**；点左上角「定位未开启」会要**定位权限**（不给也能用，只是没有地名）。
4. 建议把图标拖到桌面第一屏，或者用系统的「快捷方式」直接放到锁屏/侧边栏——这个 App 的价值就在「少点一次」。

## 四、数据存在哪 / 怎么备份

| 内容 | 位置 |
|---|---|
| 记录索引 | `/data/data/com.worklog.app/files/entries.json` |
| 照片、视频、封面 | `/data/data/com.worklog.app/files/media/` |
| 导出的 Word | 缓存目录 `exports/`，分享时通过 FileProvider 授权读取 |

- 卸载 App 会**连同照片一起删掉**，重要记录请及时导出 Word。
- 想整体备份：用 `adb backup`，或直接导出每天的 Word 归档。

## 五、已知限制（第一版故意没做）

- **没有编辑已有备注的入口**：目前只能在拍照后的浮层里写。下一版补。
- **视频不嵌入 Word**：只嵌首帧截图，因为 Word 里嵌视频兼容性很差。
- **不做云同步**：数据只在本机，换手机需要手动搬。
- **没有 AI 自动描述**：按你的选择先做「语音转文字」，AI 看图写描述留到第二版。
- **没有加水印/时间戳**：需要的话下一版加。

## 六、目录结构

```
app/src/main/java/com/worklog/app/
├── MainActivity.kt          两个 Tab 的壳
├── data/
│   ├── Entry.kt             一条记录
│   └── EntryStore.kt        JSON 索引 + media 目录
├── ui/
│   ├── CaptureScreen.kt     CameraX 预览、快门、录像、备注浮层、语音
│   ├── LogScreen.kt         按日回看、删除、导出
│   └── Theme.kt
├── util/
│   ├── Locator.kt           系统 LocationManager + Geocoder（不依赖 GMS）
│   └── Media.kt             视频导入、抽帧、缩略图解码
└── export/
    ├── DocxWriter.kt        手写 OOXML 生成 .docx
    └── Exporter.kt          落盘 + FileProvider 授权
```

## 七、下一步可以加什么

- 备注二次编辑、长按拖拽排序
- 一键补拍「同一地点连拍」
- 自定义日志模板（项目名、客户名、工时字段）
- 给照片打时间地点水印
- 接 AI：拍照后自动生成一句工作描述
