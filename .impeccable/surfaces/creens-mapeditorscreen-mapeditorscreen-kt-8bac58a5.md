---
version: 1
slug: "creens-mapeditorscreen-mapeditorscreen-kt-8bac58a5"
primary_target: "public/Unciv4iOS/core/src/com/unciv/ui/screens/mapeditorscreen/MapEditorScreen.kt"
related_targets: ["public/Unciv4iOS/core/src/com/unciv/ui/screens/mapeditorscreen/MapEditorChrome.kt"]
---

# 原生地图编辑器 · 方案 A

用户已选择 A「随手画盘」，面向新手与常用编辑兼顾。范围是 Kotlin/libGDX 原生地图编辑器，沿用现有规则集、字体、地图素材和领域行为。旧三套原型保留为历史比较。

## Direction contract

THESIS: 地图是工作主体；底部两层画盘让选素材、点画、换素材连续完成，避免反复打开侧抽屉。

OWN-WORLD: 延续游戏远征蓝，以深海蓝面板、浅色文字、沙金色保存与明确选中态形成 A 的工具语汇；地图纹理来自游戏。字体保留原生 BitmapFont，所有新控件按44pt缩放。

STORY: 首屏可直接绘制；用户知道当前工具、笔刷范围、是否未保存。绘制、移动、查看互斥。高级能力在“更多”内按原有名称可达。

FIRST VIEWPORT: 上栏返回、地图名称、保存状态、读取、保存和更多；地图保留主体；右上三种模式及缩放、撤销重做；下方七类横向工具和素材画盘，可收起。手机竖屏工具分行；iPad材料空间更宽。标志交互是保持地图可见的连续换笔刷，状态变化直接反馈，不额外引入装饰动画。

FORM: 用户已选 A，Operate / code-led；参考已交付的 previews/a-landscape.png、a-portrait.png、a-ipad.png。沿用原型方向，不重开候选轮次；seed: none (user-selected existing direction).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## 验收边界

七类工具及删除、范围1–5/连片填充、生成/分步生成、查看/描述/资源数量、规则集、加载保存删除、剪贴板/Wesnoth/OD、世界环绕和叠图保留。新增互斥模式、撤销重做、持续未保存提示。真实 iPhone/iPad 模拟器截图和操作记录；浏览器不能代替原生验收。前四项原生修复保持回归通过。不声称 libGDX 已提供系统 Dynamic Type / VoiceOver。
