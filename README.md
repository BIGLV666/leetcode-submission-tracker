# LeetCode Submission Tracker

独立记录 LeetCode 中文站的题目、提交代码和最终判题结果，数据写入本地 MySQL，并按题目聚合统计。

## 本地启动

数据库连接和采集密钥直接保存在 `src/main/resources/application.yml`，本地个人使用不需要配置环境变量。

应用会连接 `127.0.0.1:3306`，自动创建 `leetcode_tracker` 数据库以及相关数据表。

```powershell
cd E:\learncard\leetcode-submission-tracker
.\mvnw.cmd spring-boot:run
```

服务启动后监听：

```text
http://127.0.0.1:8088
```

浏览器打开该地址即可查看题目总览和月度报告。月报支持切换月份，展示每日提交趋势、判题结果、语言分布和当月通过题目。

## 安装油猴脚本

1. 浏览器安装 Tampermonkey。
2. 打开 Tampermonkey 的“添加新脚本”。
3. 将 `userscript/leetcode-submission-tracker.user.js` 的内容粘贴进去并保存。
4. 打开任意 LeetCode 中文站题目并正常提交。

脚本已经内置本地服务地址和 API Key，不需要额外设置。右下角会显示等待判题、已记录或待重试状态。

## 记录内容

- 中文题目标题、slug 和题目链接
- 每次提交的代码、语言和提交时间
- Accepted、Wrong Answer、Runtime Error 等最终状态
- 运行时间、内存和通过测试数
- 同一道题的全部历史提交

提交代码使用 AES-256-GCM 加密后入库。脚本不会读取或上传 LeetCode Cookie 和密码。

## 查询统计

在油猴菜单中点击“复制按题统计 JSON”，会显示记录题数与 AC 题数，并复制完整统计。

也可以通过 PowerShell 查询：

```powershell
$headers = @{ "X-Tracker-Key" = "leetcode-tracker-local" }

Invoke-RestMethod `
  -Uri http://127.0.0.1:8088/api/v1/stats/questions `
  -Headers $headers
```

查询某道题的全部提交和解密代码：

```powershell
Invoke-RestMethod `
  -Uri http://127.0.0.1:8088/api/v1/questions/two-sum/submissions `
  -Headers $headers
```

## 月报分享卡片（网页直接生成/导出）

「月度报告」页底部的**分享卡片**区可以一键生成手账风月报卡片（KPI 药丸、打卡日历、语言占比、最难缠的题），并导出 2x PNG 发社交媒体。对应接口：

- `POST /api/v1/report/card/{year}/{month}`：重新生成（Java2D 本地绘制，不依赖外部进程），文件落在 `reports\share-YYYY-MM.png`
- `GET /api/v1/report/card/{year}/{month}/png`：下载 PNG，文件不存在会自动生成

## 每日复习清单与月报（直连数据库，无需启动服务）

`scripts/` 下有两个 Python 脚本，直接读 `leetcode_tracker` 库，不依赖 Spring Boot 运行：

```powershell
# 今天该复习哪几题（欠账 + 隔期重做 + 一发即中抽查）
python scripts\daily_review.py

# 生成浅色 HTML 月报 + 手账分享卡片 HTML（默认当前月），输出到 reports\
python scripts\monthly_report.py 2026-09

# 把分享卡片 HTML 截成 2x PNG（也可以直接用网页上的“导出 PNG”按钮）
node scripts\card_shot.cjs reports\share-2026-09.html
```

复习调度规则（无状态，全部由提交记录推导）：

- 欠账：失败后还没 AC 的题，每天列出直到做出来
- 隔期重做：失败后 AC 过的题，距上次 AC 达到间隔（失败 1/2/3+ 次对应 3/7/14 天）即到期，每天按逾期最久优先列 6 道，超 3 倍间隔视为已巩固
- 抽查：约一个月前一发即中的题，每天随机回访 2 道（同一天结果稳定）

月报为单文件 HTML（浅色主题、无 CDN 依赖），含 KPI 环比、打卡日历、判题结果、语言分布、难度分布、卡壳榜和当月攻克清单。分享卡片只保留手账风一种（用户选定），Python 版与网页版（Java2D）版式一致。

## OpenAPI 接口文档

项目引入 Springdoc，根据 Spring MVC Controller 和 DTO 自动生成 OpenAPI 3 文档。

直接请求原始 JSON：

```text
GET http://127.0.0.1:8088/v3/api-docs
```

PowerShell 查看：

```powershell
Invoke-RestMethod http://127.0.0.1:8088/v3/api-docs |
  ConvertTo-Json -Depth 20
```

浏览器交互页面：

```text
http://127.0.0.1:8088/swagger-ui.html
```

OpenAPI JSON 的主要字段：

- `openapi`：规范版本
- `info`：应用标题和版本
- `servers`：服务地址
- `paths`：按 URL 和 HTTP Method 描述请求参数、Request Body 与响应
- `components.schemas`：DTO、字段类型、必填字段和嵌套对象定义

现有 API Key 拦截器只处理 `/api/**`，文档接口可以直接访问。在 Swagger UI 中点击
`Authorize`，填写 `X-Tracker-Key` 对应的值后即可调用 `/api/v1/**`。

## 后续接入 CodeWise

接入 CodeWise 时，将固定 API Key 替换为 CodeWise JWT，并从 JWT 中读取用户 ID。现有题目表、提交表和油猴采集流程可以继续使用。
