# 第三方组件与许可

## KaTeX 0.18.7（MIT）

- **用途**：正文里的数学公式渲染，**内置在应用内（离线，不联网）**
- **位置**：`source/mark-readnotes/app/src/main/assets/ui/vendor/katex/`
  （`katex.min.js`、裁剪过的 `katex.min.css`、20 个 `.woff2` 字体；压缩后入包约 555KB）
- **获取方式**：从 npm 镜像拉 tarball，并**校验过 npm 声明的 `dist.integrity`（sha512）一致**：
  `https://registry.npmmirror.com/katex/-/katex-0.18.7.tgz`
  tarball sha256 `9a80a3fba2367e99bf67b52bfff52e9534c8c7f198e4eaa12699e4f1df9a0bda`
- **裁剪说明**：CSS 里只保留 `woff2` 字体回退（去掉 woff/ttf，安卓 WebView 都支持 woff2），代码未改
- **许可全文**：`evidence/katex-LICENSE-MIT.txt`；来源与校验记录：`evidence/katex-provenance.txt`

## AndroidX WebKit 1.11.0（Apache-2.0）

- **用途**：`WebViewCompat.addWebMessageListener`（按 origin 开放的 JS↔原生通道）
- 由 Gradle 从阿里云镜像解析，未改代码

## 其它

- 前端（`assets/ui/app.js`、`app.css`、`index.html`）与 markdown 渲染器均为本项目自己写的，无第三方依赖
- 构建工具链（Gradle / AGP / Kotlin 编译器）不随产物分发
