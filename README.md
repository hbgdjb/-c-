# 小熊猫C++ 安卓版 (RedPanda Android)

一个**安卓原生的 C/C++ 集成开发环境**，界面与用法参考桌面版「小熊猫C++」（RedPanda-C++），并针对**手机触屏**重新设计了交互。

**内置完整 C/C++ 编译器**（clang 21 + lld + lldb 调试器 + clangd），装上就能写代码、编译、运行、调试 —— 不需要联网、不需要 Termux、不需要 root。

---

## 功能

| 分类 | 功能 |
|---|---|
| **编辑** | C/C++ 语法高亮（TextMate）、多标签页、**代码片段**（22 个常用模板）、**输入补全**（关键字 + 片段触发词 + 文档标识符）、**查找/替换**（支持大小写敏感与正则）、**竖排/横排符号快捷输入栏**、注释/缩进/撤销/重做、**自动格式化**（按大括号层级重新缩进） |
| **编译运行** | 一键编译运行 / 仅编译、**单文件与多文件编译**、控制台实时输出、**stdin 输入**、**编译错误点击跳转** |
| **调试** | **断点**（步入/步过/步出/继续）、**变量查看**、**调用栈**、调试编译自动加 `-g` |
| **代码导航** | **跳转定义**（clangd 实现，支持本文件 / 同目录 / 系统头文件） |
| **文件管理** | **文件树**（目录展开、新建文件/文件夹、重命名、删除） |
| **界面** | **竖屏 / 横屏两套布局**（横屏为左右分屏：竖排工具栏 + 编辑器 + 侧面板）、亮/暗/跟随系统配色、字体大小调节 |
| **存储** | 占用明细 + 一键清理编译产物与临时文件 |
| **自检** | 一键检测编译器 / 运行 / 调试器是否可用 |

---

## 构建

**环境要求**：JDK 17、Android SDK（platform-tools / android-35 / build-tools 35.0.0）、Gradle 8.11.1

```bash
git clone https://github.com/<你的用户名>/RedPandaAndroid.git
cd RedPandaAndroid

# 复制签名配置模板并填入真实值
cp keystore.properties.template keystore.properties

./gradlew assembleDebug      # 调试版
./gradlew assembleRelease    # 发布版
```

产物在 `app/build/outputs/apk/{debug,release}/`。

**关键版本**：
- Kotlin **2.2.21**（Sora Editor 0.24.4 的硬性要求）
- AGP 8.7.3 / Gradle 8.11.1 / Compose BOM 2024.12.01
- `targetSdk = 28`（规避 Android 10+ 对私有目录文件的执行限制 W^X）
- `minSdk = 24`、`abiFilters = arm64-v8a`

**改内置工具链后必须递增 `ToolchainManager.revision`**，否则老用户升级不会重新解压。

---

## 内置工具链

`app/src/main/assets/toolchain/ndk-aarch64.tar.xz`（约 82MB）包含：

```
bin/
  clang-21          C/C++ 编译器（75MB，静态 musl）
  lld               链接器（52MB）
  lldb              LLDB 调试器（88MB）
  lldb-server       调试服务端（28MB）
  clangd            代码导航（69MB）
  clang / clang++ / cc / ld.lld   → 符号链接
lib/clang/21/       内建头文件与 compiler-rt
sysroot/            完整 sysroot 与 libc++（只保留 API 24）
```

来源：[termux-ndk r29](https://github.com/lzhiyong/termux-ndk) 的 aarch64 版 Android NDK，
经裁剪（去掉 llvm-ar、非 ARM 头文件、多余 API 版本）后从 237MB 压到 203MB。

工具链在**首次启动时解压**到应用私有目录并赋予可执行权限；`.installed` 标记带版本号，
`revision` 变化时会自动重新解压。

> ⚠️ 工具链二进制是 aarch64 的，**x86 模拟器无法运行**，请用真机测试。

---

## 目录结构

```
RedPandaAndroid/
├── app/src/main/java/com/redpanda/cppide/
│   ├── MainActivity.kt            主界面（竖屏/横屏两套布局）
│   ├── App.kt                     TextMate 语法/主题初始化
│   ├── compiler/                  ToolchainManager / Compiler / ProgramRunner
│   ├── debugger/                  LldbDriver / DebugController（LLDB 交互）
│   ├── navigator/                 ClangdClient / NavigationController（LSP）
│   ├── editor/                    EditorActions / CppCompletionLanguage
│   ├── workspace/                 Workspace / TemplateManager / StorageManager
│   └── ui/                        Theme / EditorBars / FindReplacePanel / FileTreePanel /
│                                  SettingsDialog / AboutDialog / SnippetPanel /
│                                  DebugPanel / SelfCheckDialog
├── app/src/main/assets/           toolchain / templates / textmate
└── app/src/main/res/              图标 / 主题 / 字符串 / 备份规则
```

---

## 许可

本项目以 **GPL-3.0** 发布（因复用了 GPLv3 素材）。

### 原作来源

本项目参考并复刻自桌面版「小熊猫C++」（RedPanda-C++）。
- 作者：Roy Qu
- 仓库：https://github.com/royqh1979/RedPanda-CPP
- 官网：http://royqh.net/redpandacpp
- 许可：GPL-3.0

### 第三方组件

| 组件 | 许可 | 用途 |
|---|---|---|
| [Sora Editor](https://github.com/Rosemoe/sora-editor) | LGPL-2.1 | 代码编辑器与语法高亮 |
| [termux-ndk r29](https://github.com/lzhiyong/termux-ndk) | Android SDK / Apache-2.0 | 内置 aarch64 工具链 |
| Android NDK r29 | Apache-2.0 | clang / lld / lldb / clangd |
| Jetpack Compose / AndroidX | Apache-2.0 | 界面框架 |
| commons-compress / XZ for Java | Apache-2.0 | 首启解压工具链 |

若你认为本项目侵犯了你的权益，请通过仓库 Issue 联系，我会及时处理。
