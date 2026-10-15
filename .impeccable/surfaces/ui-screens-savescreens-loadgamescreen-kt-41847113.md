---
version: 1
slug: "ui-screens-savescreens-loadgamescreen-kt-41847113"
primary_target: "public/Unciv4iOS/core/src/com/unciv/ui/screens/savescreens/LoadGameScreen.kt"
related_targets: ["public/Unciv4iOS/core/src/com/unciv/ui/screens/savescreens/LoadGameScreenStyle.kt","public/Unciv4iOS/core/src/com/unciv/ui/screens/savescreens/LoadGameSaveList.kt"]
---

# 读取游戏 A 原生实现

Scope: public/Unciv4iOS/core/src/com/unciv/ui/screens/savescreens/LoadGameScreen.kt 与本页专属样式、列表、模型、测试；Operate；用户于2026-10-15选定 A「双栏速览」，要求保留功能，在 iPhone/iPad 模拟器完整测试，并推送当前项目所有非敏感改动。
Approved reference: docs/design/load-game-prototypes-20261015/images/a-populated.png、a-empty.png 和 screen.css/screen.js 的 A 分支。B/C 只保留候选档案。

## Direction contract
THESIS: 原生读取页落实 A：列表直接识别文明、回合和时间，选档同时更新右侧详情，读取位置固定。
OWN-WORLD: 继承 Expedition Blue 精确配色、12–16pt圆角和统一线图标。金色只用于选择、主操作与焦点。保留游戏 BitmapFont 与字体设置，不改全局字体。
STORY: 从本机存档进入真实游戏；文件/剪贴板导入、云状态/同步/恢复、自动存档筛选、复制、本机删除、键盘选择与双击读取均保留。损坏存档不能读取，仍可复制/删除/导入恢复。
FIRST VIEWPORT: 横屏左右两栏约各半，左列无额外大面板，列表头与自动存档同排，右侧蓝灰圆角详情，底部金色读取及一行管理操作。顶部仅返回、标题、副标题、iCloud状态入口与导入。窄视口先列表再详情，平板继续双栏；空态保留导入与云恢复。
FORM: 用户选定原型 A 为构图契约；seed: none (unavailable)。单页原生落地不再抽签；选择状态和读取动作在同一视图内保持关联，适配依据安全区点尺寸。
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Verification: 必须使用最终代码对应模拟器安装包，覆盖 iPhone/iPad 横竖屏、中英、大字体、有/无存档、损坏存档、长名字、所有入口与真实读取。云端真实账号同步若模拟器无账号，明确记录限制，隔离服务状态测试不可替代云端实测。仅清理本任务构建和中间文件，caffeinate PID9116覆盖全程。
