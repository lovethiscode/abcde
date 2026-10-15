---
version: 1
slug: "screens-modmanager-modmanagementscreen-kt-d4d6d70f"
primary_target: "public/Unciv4iOS/core/src/com/unciv/ui/screens/modmanager/ModManagementScreen.kt"
related_targets: ["public/Unciv4iOS/core/src/com/unciv/ui/screens/modmanager/ModInfoAndActionPane.kt"]
---

# 模组页面 A 原生实现

Scope: public/Unciv4iOS/core/src/com/unciv/ui/screens/modmanager。Operate；用户已选择既有蓝色 A 方案并要求编译、安装、测试。现有原型和四张原始模拟器截图仍在 docs/design/mods-prototypes。

## Direction contract
THESIS: 让横屏玩家在同一视野中检索模组、连续比较详情并安装；整理现有管理功能的层级。
OWN-WORLD: 继承 ExpeditionBlueTheme，背景与主界面共同引用 background；沿用游戏字体与蓝色面板、金色主操作。原型系统字体允许替换为现有游戏 BitmapFont。
STORY: 发现或已安装 → 搜索/分类/当前列表排序 → 选择条目 → 读取事实和说明 → 底部下载/更新。GitHub、检查、删除与常驻音画保留；删除须确认，内置规则集不能删除。
FIRST VIEWPORT: 单行顶部导航和添加入口；左46%检索、计数和滚动列表；右54%标题、同排事实、滚动正文与固定操作栏。窄宽和大字体退为单栏列表/详情，返回保留筛选和排序。
FORM: 用户明确选择既有原型A，沿用现有世界，seed: none (unavailable)。新增图标是少量同描边线段，不新增位图资产。新翻译提供中英繁中，其余使用既有翻译回退。
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Validation: 标准私有启动器，无 UI-test probe；现有项目 XCTest 驱动操作真实界面，simctl 设备原始截图为视觉证据。桌面 CUA 坐标偏移未作为通过。219项iOS测试，最终构建另行记录；原有存档哈希对照，远端不push。

## Finish outcome
内部版1451已安装iPhone与iPad，219项iOS测试通过，3805类RoboVM兼容性门通过。独立完整原生review提出3项材料修复，1451 verdict仅对这3项评分为resolved、disposition ship。证据、未覆盖范围与原数据恢复记录位于 docs/design/mods-prototypes/native-validation/；未push。
